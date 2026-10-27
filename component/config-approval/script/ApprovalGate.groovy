/*
 * Config approval gate, run from SECAs on guarded services (see service/config-approval.secas.xml).
 * Context in: gatePhase ("hold" in pre-validate, "record" in post-service), gateServiceName, and for hold the
 * live service `parameters` map (stripping from it is what keeps guarded fields out of the real call).
 */
import groovy.json.JsonOutput
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl

final String REPLAY = "ccr.replay"    // requestId being applied by apply#ConfigChangeRequest
final String PENDING = "ccr.pending"  // List<Map> held changes, saved by the record phase

Map shared = ((ExecutionContextImpl) ec).contextStack.getSharedMap()

if (gatePhase == "hold") {
    if (shared.get(REPLAY)) return
    if ("false".equals(System.getProperty("config.approval.enabled"))) return
    // scheduler, data load, startup etc have no user: not a person making a change, so not gated
    String userId = ec.user.userId
    if (!userId) return
    if (ec.user.isInGroup("CONFIG_APPROVAL_BYPASS")) return

    // policy: guarded fields of the entity behind this service
    // wrapper services (e.g. JobServices.update#ServiceJob) name the entity explicitly; entity-auto services imply it
    String entityName = ec.context.get("gateEntityName") ?: gateServiceName.substring(gateServiceName.indexOf('#') + 1)
    List<EntityValue> guardList = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "ConfigApprovalField")
            .condition("enumCode", org.moqui.entity.EntityCondition.LIKE, entityName + ".%").disableAuthz().list()
    Map<String, EntityValue> guarded = [:]
    for (EntityValue g in guardList) {
        if ("N".equals(g.optionValue)) continue
        String f = g.enumCode.substring(entityName.length() + 1)
        if (f != "*" && f != "-") guarded.put(f, g)
    }
    if (!guarded) return

    // current record, read straight from the DB (no cache, no tx cache)
    List<String> pkNames = ec.entity.getEntityDefinition(entityName).getPkFieldNames()
    Map pkMap = new TreeMap()
    for (String pk in pkNames) pkMap.put(pk, parameters.get(pk))
    if (pkMap.values().any { it == null }) return
    EntityValue current = ec.entity.find(entityName).condition(pkMap).useCache(false).disableAuthz().one()
    if (current == null) return   // create path is not held in the POC

    // normalize so "no change" is not reported: null/"" equal, and a null paused means active (same as N)
    Closure norm = { String field, Object v ->
        if (v == null || "".equals(v)) return field == "paused" ? "N" : null
        return v instanceof java.sql.Timestamp || v instanceof Date ? ((Date) v).time : v.toString()
    }
    List<Map> diff = []
    Map original = [:]
    Map held = [:]
    for (String f in guarded.keySet()) {
        if (!parameters.containsKey(f)) continue
        Object oldV = current.get(f)
        // convert the incoming value to the field's type the same way the entity layer will
        EntityValue converted = ec.entity.makeValue(entityName)
        converted.set(f, parameters.get(f))
        Object newV = converted.get(f)
        if (norm(f, oldV) == norm(f, newV)) continue
        boolean secret = "Y".equals(guarded.get(f).optionIndicator)
        diff.add([field: f, oldValue: secret ? "(changed)" : (oldV == null ? null : oldV.toString()),
                  newValue: secret ? "(changed)" : (newV == null ? null : newV.toString())])
        original.put(f, oldV == null ? null : oldV.toString())
        held.put(f, parameters.get(f))
    }
    // custom params are not declared on the service so validation would drop them: take them from the live map now
    Object reasonParam = parameters.remove("_changeReason")
    Object plannedFixParam = parameters.remove("_plannedFixDate")
    if (!diff) return
    held.each { String f, Object v -> parameters.remove(f) }

    String channel = ec.web != null ? "rest" : "service"
    List pending = (List) shared.get(PENDING)
    if (pending == null) { pending = []; shared.put(PENDING, pending) }
    String pkJson = JsonOutput.toJson(pkMap)
    String summary = (entityName == "moqui.service.job.ServiceJob" && held.containsKey("paused"))
            ? ((held.get("paused") == "Y" ? "Pause" : "Resume") + " job " + pkMap.values().join("/"))
            : ("Change " + entityName.substring(entityName.lastIndexOf('.') + 1) + " " + pkMap.values().join("/") + " (" + held.keySet().join(", ") + ")")
    pending.add([serviceName: gateServiceName, entityName: entityName, pkJson: pkJson,
            changeSourceEnumId: "CcsApproval", changeTypeEnumId: "CctUpdate",
            parametersJson: JsonOutput.toJson(pkMap + held), originalJson: JsonOutput.toJson(original),
            diffJson: JsonOutput.toJson(diff), summaryText: summary,
            reason: reasonParam, plannedFixDate: plannedFixParam, requestChannel: channel])
} else if (gatePhase == "record") {
    List pending = (List) shared.remove(PENDING)
    if (!pending) return
    for (Map h in pending) {
        Map out = ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalServices.create#ConfigChangeRequest")
                .parameters(h).disableAuthz().call()
        ec.message.addMessage("Request sent for approval (CR ${out.requestId}). You will get an email when it is decided.".toString(), "success")
        ec.web?.response?.addHeader("X-Config-Change-Request", out.requestId as String)
    }
}
