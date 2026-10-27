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
    // pre-validate rules can run more than once on the same parameters map; a break-glass decision already made for this map stands
    if (shared.get("ccr.breakglass") == System.identityHashCode(parameters)) return
    if ("false".equals(System.getProperty("config.approval.enabled"))) return
    // scheduler, data load, startup etc have no user: not a person making a change, so not gated
    String userId = ec.user.userId
    if (!userId) return
    if (ec.user.isInGroup("CONFIG_APPROVAL_BYPASS")) return

    // policy: guarded fields of the entity behind this service
    // wrapper services (e.g. JobServices.update#ServiceJob) name the entity explicitly; entity-auto services imply it
    String entityName = ec.context.get("gateEntityName") ?: gateServiceName.substring(gateServiceName.indexOf('#') + 1)
    List<EntityValue> guardList = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "ConfigApprovalField")
            .condition("enumCode", org.moqui.entity.EntityCondition.LIKE, entityName + ".%").disableAuthz().useCache(false).list()
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
    String verb = gateServiceName.substring(0, gateServiceName.indexOf('#'))
    verb = verb.substring(verb.lastIndexOf('.') + 1)
    boolean isCreate = verb == "create" || (verb == "store" && current == null)
    boolean isDelete = verb == "delete"
    if (isCreate && current != null) return   // the service will refuse a duplicate itself
    if (current == null && !isCreate) return   // update or delete of a record that is not there: the service reports it

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
        Object oldV = current?.get(f)
        // convert the incoming value to the field's type the same way the entity layer will
        EntityValue converted = ec.entity.makeValue(entityName)
        converted.set(f, parameters.get(f))
        Object newV = converted.get(f)
        if (norm(f, oldV) == norm(f, newV)) continue
        // edit forms pre-fill an empty fromDate with "now"; a start that is already in the past on a job with no start does nothing
        if (f == "fromDate" && oldV == null && newV instanceof java.sql.Timestamp && ((java.sql.Timestamp) newV).time <= System.currentTimeMillis()) {
            // drop it rather than let it through: written directly it would be flagged as an ungated change
            parameters.remove(f)
            continue
        }
        boolean secret = "Y".equals(guarded.get(f).optionIndicator)
        diff.add([field: f, oldValue: secret ? "(changed)" : (oldV == null ? null : oldV.toString()),
                  newValue: secret ? "(changed)" : (newV == null ? null : newV.toString())])
        original.put(f, oldV == null ? null : oldV.toString())
        held.put(f, parameters.get(f))
    }
    // custom params are not declared on the service so validation would drop them: take them from the live map now
    Object reasonParam = parameters.remove("_changeReason")
    Object plannedFixParam = parameters.remove("_plannedFixDate")
    Object breakGlassParam = parameters.remove("_breakGlass")
    if (isCreate || isDelete) {
        // a create or delete has no field to strip, and a pre-validate rule can only stop a service by adding an error. So the
        // request is saved in its own transaction (it survives the roll back) and the service is stopped with a message that
        // says the request was sent. Break-glass does not apply here.
        if (shared.get("ccr.held") == System.identityHashCode(parameters)) return
        def ed = ec.entity.getEntityDefinition(entityName)
        Map createHeld = [:]
        List createDiff = []
        Map deleteOriginal = [:]
        if (isCreate) {
            for (String f in new ArrayList(parameters.keySet())) {
                if (pkNames.contains(f) || !ed.isField(f) || parameters.get(f) == null || "".equals(parameters.get(f))) continue
                createHeld.put(f, parameters.get(f))
                boolean secretF = guarded.containsKey(f) && "Y".equals(guarded.get(f).optionIndicator)
                createDiff.add([field: f, oldValue: null, newValue: secretF ? "(changed)" : parameters.get(f).toString()])
            }
        } else {
            for (String f in guarded.keySet()) {
                Object v = current.get(f)
                if (v == null) continue
                boolean secretF = "Y".equals(guarded.get(f).optionIndicator)
                createDiff.add([field: f, oldValue: secretF ? "(changed)" : v.toString(), newValue: "(deleted)"])
                deleteOriginal.put(f, v.toString())
            }
            if (!createDiff) createDiff.add([field: "(record)", oldValue: "exists", newValue: "(deleted)"])
        }
        String shortName = entityName.substring(entityName.lastIndexOf('.') + 1)
        String summaryC = (isCreate ? "Add " : "Delete ") + shortName + " " + pkMap.values().join("/")
        Map outC = ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalServices.create#ConfigChangeRequest")
                .parameters([serviceName: gateServiceName, entityName: entityName, pkJson: JsonOutput.toJson(pkMap),
                        changeSourceEnumId: "CcsApproval", changeTypeEnumId: isCreate ? "CctCreate" : "CctDelete",
                        parametersJson: JsonOutput.toJson(pkMap + createHeld), originalJson: JsonOutput.toJson(deleteOriginal),
                        diffJson: JsonOutput.toJson(createDiff), summaryText: summaryC, reason: reasonParam, plannedFixDate: plannedFixParam,
                        requestChannel: ec.web != null ? "rest" : "service"])
                .requireNewTransaction(true).disableAuthz().call()
        shared.put("ccr.held", System.identityHashCode(parameters))
        ec.web?.response?.addHeader("X-Config-Change-Request", outC.requestId as String)
        ec.message.addError("Request sent for approval (CR ${outC.requestId}). Nothing was ${isCreate ? 'added' : 'deleted'} yet; you will get an email when it is decided.".toString())
        return
    }
    if (!diff) return
    // break-glass: explicit (_breakGlass=Y), only for CONFIG_BREAK_GLASS members, and only with a reason. Anything else is held as usual.
    boolean breakGlass = "Y".equals(breakGlassParam) && ec.user.isInGroup("CONFIG_BREAK_GLASS") && reasonParam
    if (!breakGlass) held.each { String f, Object v -> parameters.remove(f) }
    else shared.put("ccr.breakglass", System.identityHashCode(parameters))   // tells the ungated-write EECA this direct write is accounted for

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
            reason: reasonParam, plannedFixDate: plannedFixParam, requestChannel: channel, breakGlass: breakGlass])
} else if (gatePhase == "record") {
    shared.remove("ccr.breakglass")
    List pending = (List) shared.remove(PENDING)
    if (!pending) return
    for (Map h in pending) {
        if (h.remove("breakGlass")) {
            // the change goes through as written; keep a record and tell the approvers
            h.remove("changeSourceEnumId"); h.remove("changeTypeEnumId")
            Map bg = ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalServices.record#BreakGlassChange")
                    .parameters(h).disableAuthz().call()
            ec.message.addMessage("Break-glass change applied (CR ${bg.requestId}). Approvers have been told.".toString(), "warning")
            continue
        }
        Map out = ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalServices.create#ConfigChangeRequest")
                .parameters(h).disableAuthz().call()
        ec.message.addMessage("Request sent for approval (CR ${out.requestId}). You will get an email when it is decided.".toString(), "success")
        ec.web?.response?.addHeader("X-Config-Change-Request", out.requestId as String)
    }
}
