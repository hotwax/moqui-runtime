/*
 * Safety net, run from entity/config-approval.eecas.xml after an update of a guarded entity. The service gate strips guarded
 * fields before the write, so a guarded field that still changes here came from a path that skipped it (a direct entity write,
 * an auto-edit screen, a script). The write has already happened, so this only records it as an applied request flagged
 * CcsUngated, which the digest reports. Context in: entityValue, originalValue (from the EECA).
 */
import groovy.json.JsonOutput
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl

// a failure here must never block the write it is observing
try {
    if (originalValue == null) return
    if (((ExecutionContextImpl) ec).contextStack.getSharedMap().get("ccr.replay")) return   // our own apply#
    if (((ExecutionContextImpl) ec).contextStack.getSharedMap().get("ccr.breakglass")) return  // recorded as break-glass by the gate
    if ("false".equals(System.getProperty("config.approval.enabled"))) return
    if (!ec.user.userId) return                                  // scheduler, data load, startup: not a person
    if (ec.user.isInGroup("CONFIG_APPROVAL_BYPASS")) return      // bypass accounts are allowed to write directly

    // eecaEntityName is set by the rule: the EECA context does not expose the entity name
    String entityName = eecaEntityName
    List<EntityValue> guardList = ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "ConfigApprovalField")
            .condition("enumCode", org.moqui.entity.EntityCondition.LIKE, entityName + ".%").disableAuthz().list()
    Closure norm = { String field, Object v ->
        if (v == null || "".equals(v)) return field == "paused" ? "N" : null
        return v instanceof java.sql.Timestamp || v instanceof Date ? ((Date) v).time : v.toString()
    }
    List<Map> diff = []
    Map original = [:], now = [:]
    for (EntityValue g in guardList) {
        if ("N".equals(g.optionValue)) continue
        String f = g.enumCode.substring(entityName.length() + 1)
        if (f == "*" || f == "-" || !entityValue.containsKey(f)) continue
        Object oldV = originalValue.get(f), newV = entityValue.get(f)
        if (norm(f, oldV) == norm(f, newV)) continue
        boolean secret = "Y".equals(g.optionIndicator)
        diff.add([field: f, oldValue: secret ? "(changed)" : oldV?.toString(), newValue: secret ? "(changed)" : newV?.toString()])
        original.put(f, oldV?.toString()); now.put(f, newV?.toString())
    }
    if (!diff) return

    Map pkMap = new TreeMap()
    for (String pk in ec.entity.getEntityDefinition(entityName).getPkFieldNames()) pkMap.put(pk, entityValue.get(pk))
    String summary = (entityName == "moqui.service.job.ServiceJob" && now.containsKey("paused"))
            ? ((now.get("paused") == "Y" ? "Pause" : "Resume") + " job " + pkMap.values().join("/") + " (written directly, not approved)")
            : ("Change " + entityName.substring(entityName.lastIndexOf('.') + 1) + " " + pkMap.values().join("/") + " (" + now.keySet().join(", ") + ", written directly, not approved)")
    java.sql.Timestamp ts = ec.user.nowTimestamp
    ec.entity.makeValue("co.hotwax.configapproval.ConfigChangeRequest").setAll([
            changeSourceEnumId: "CcsUngated", changeTypeEnumId: "CctUpdate", entityName: entityName, pkJson: JsonOutput.toJson(pkMap),
            serviceName: "(direct entity write)", parametersJson: JsonOutput.toJson(now), originalJson: JsonOutput.toJson(original),
            diffJson: JsonOutput.toJson(diff), summaryText: summary, reasonMissing: "Y", statusId: "CcrApplied",
            requestedByUserId: ec.user.userId, requestedDate: ts, appliedDate: ts, requestChannel: "entity"]).setSequencedIdPrimary().create()
} catch (Throwable t) {
    ec.logger.error("Config approval ungated-write check failed for ${eecaEntityName}: ${t.toString()}", t)
}
