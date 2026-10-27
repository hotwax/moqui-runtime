import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import java.sql.Timestamp
import spock.lang.*

/** POC checks for the gate on ServiceJob.paused. Run against an isolated H2 runtime (see README in the POC notes). */
@Stepwise
class ConfigApprovalPocTests extends Specification {
    @Shared ExecutionContext ec
    static final String CCR = "co.hotwax.configapproval.ConfigChangeRequest"
    static final String SVC = "co.hotwax.configapproval.ConfigApprovalServices."

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        // alice = maker, bob and dave = approvers, carol = neither
        for (String u in ["alice", "bob", "carol", "dave"]) {
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "poc_" + u, username: "poc_" + u,
                    userFullName: u.capitalize(), emailAddress: u + "@example.com", currentPassword: "x"]).createOrUpdate()
        }
        for (String u in ["bob", "dave"]) ec.entity.makeValue("moqui.security.UserGroupMember")
                .setAll([userGroupId: "CONFIG_APPROVERS", userId: "poc_" + u, fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).createOrUpdate()
        // all four may use the job entity/service and the approval services; test 4 adds a DENY on top for carol
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "POC_USERS"]).createOrUpdate()
        for (String u in ["alice", "bob", "carol", "dave"]) ec.entity.makeValue("moqui.security.UserGroupMember")
                .setAll([userGroupId: "POC_USERS", userId: "poc_" + u, fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).createOrUpdate()
        ec.entity.makeValue("moqui.security.ArtifactGroup").setAll([artifactGroupId: "POC_ALLOW_ALL"]).createOrUpdate()
        [[AT_ENTITY: "moqui.service.job.ServiceJob"], [AT_ENTITY: "co.hotwax.configapproval.ConfigChangeRequest"],
         [AT_SERVICE: "update#moqui.service.job.ServiceJob"], [AT_SERVICE: "co.hotwax.configapproval.*"], [AT_SERVICE: "co.hotwax.job.JobServices.*"], [AT_XML_SCREEN: "component://config-approval/screen/ChangeApproval.*"]].each { Map m ->
            ec.entity.makeValue("moqui.security.ArtifactGroupMember").setAll([artifactGroupId: "POC_ALLOW_ALL", artifactTypeEnumId: m.keySet().first(),
                    artifactName: m.values().first(), nameIsPattern: m.values().first().endsWith("*") ? "Y" : "N", inheritAuthz: "Y"]).createOrUpdate()
        }
        ec.entity.makeValue("moqui.security.ArtifactAuthz").setAll([artifactAuthzId: "POC_ALLOW_ALL", userGroupId: "POC_USERS", artifactGroupId: "POC_ALLOW_ALL",
                authzTypeEnumId: "AUTHZT_ALLOW", authzActionEnumId: "AUTHZA_ALL"]).createOrUpdate()
        ec.transaction.commit()
        ec.artifactExecution.enableAuthz()
    }
    def cleanupSpec() { ec.destroy() }

    // ---- helpers
    private void makeJob(String name, String paused = "N") {
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.service.job.ServiceJob").setAll([jobName: name, description: "poc job", serviceName: "org.moqui.impl.UtilityServices.noop", cronExpression: "0 0 * * * ?", paused: paused]).createOrUpdate()
        ec.artifactExecution.enableAuthz()
    }
    private Map as_(String who, Closure c) {
        ec.user.internalLoginUser("poc_" + who)
        try { return c.call() as Map } finally { ec.user.logoutUser() }
    }
    private Map callUpdate(String who, Map params) {
        as_(who) { ec.service.sync().name("update#moqui.service.job.ServiceJob").parameters(params).call() }
    }
    private EntityValue job(String name) { ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", name).useCache(false).disableAuthz().one() }
    private List<EntityValue> ccrs(String jobName) {
        ec.entity.find(CCR).condition("pkJson", '{"jobName":"' + jobName + '"}').orderBy("requestedDate").disableAuthz().useCache(false).list()
    }

    def "1 pause is held, other fields in the same edit still save, maker sees a success message not an error"() {
        given: makeJob("poc_j1")
        when:
        ec.message.clearAll()
        callUpdate("alice", [jobName: "poc_j1", paused: "Y", description: "edited", _changeReason: "reindex running", _plannedFixDate: "2026-10-29 10:00:00"])
        def msgs = ec.message.messages.collect { it.toString() }
        def errs = ec.message.errors
        def reqs = ccrs("poc_j1")
        then:
        job("poc_j1").paused == "N"
        job("poc_j1").description == "edited"
        errs.isEmpty()
        msgs.any { it.contains("Request sent for approval") }
        reqs.size() == 1
        reqs[0].statusId == "CcrPending"
        reqs[0].requestedByUserId == "poc_alice"
        reqs[0].reason == "reindex running"
        reqs[0].reasonMissing == "N"
        reqs[0].summaryText == "Pause job poc_j1"
        reqs[0].diffJson.contains('"newValue":"Y"')
        reqs[0].plannedFixDate != null
        reqs[0].expireDate != null
    }

    def "2 an edit that does not change paused creates no request"() {
        given: makeJob("poc_j2")
        when:
        callUpdate("alice", [jobName: "poc_j2", paused: "N", description: "just a description"])
        callUpdate("alice", [jobName: "poc_j2", description: "again"])
        then:
        ccrs("poc_j2").isEmpty()
        job("poc_j2").description == "again"
    }

    def "3 scheduler-style system update (no user) is not gated"() {
        given: makeJob("poc_j3")
        when:
        ec.artifactExecution.disableAuthz()
        ec.service.sync().name("update#moqui.service.job.ServiceJob").parameters([jobName: "poc_j3", paused: "Y"]).call()
        ec.artifactExecution.enableAuthz()
        then:
        ec.user.userId == null
        job("poc_j3").paused == "Y"
        ccrs("poc_j3").isEmpty()
    }

    def "4 authorization failure after the hold leaves no request"() {
        given:
        makeJob("poc_j4")
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "POC_DENIED"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "POC_DENIED", userId: "poc_carol", fromDate: new Timestamp(System.currentTimeMillis() - 86400000L)]).createOrUpdate()
        ec.entity.makeValue("moqui.security.ArtifactGroup").setAll([artifactGroupId: "POC_DENY_SJ_UPDATE"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.ArtifactGroupMember").setAll([artifactGroupId: "POC_DENY_SJ_UPDATE", artifactTypeEnumId: "AT_ENTITY", artifactName: "moqui.service.job.ServiceJob", inheritAuthz: "N"]).createOrUpdate()
        ec.entity.makeValue("moqui.security.ArtifactAuthz").setAll([artifactAuthzId: "POC_DENY_SJ", userGroupId: "POC_DENIED", artifactGroupId: "POC_DENY_SJ_UPDATE", authzTypeEnumId: "AUTHZT_DENY", authzActionEnumId: "AUTHZA_ALL"]).createOrUpdate()
        ec.artifactExecution.enableAuthz()
        ec.message.clearAll()
        when:
        Throwable denied = null
        // implicit entity-auto services are authorized at the entity (no service-level check), so the deny throws out of the call
        try { callUpdate("carol", [jobName: "poc_j4", paused: "Y", _changeReason: "should not matter"]) } catch (Throwable t) { denied = t }
        then:
        denied != null || ec.message.hasError()
        job("poc_j4").paused == "N"
        ccrs("poc_j4").isEmpty()
        cleanup:
        ec.message.clearAll()
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.security.ArtifactAuthz").condition("artifactAuthzId", "POC_DENY_SJ").deleteAll()
        ec.entity.find("moqui.security.ArtifactGroupMember").condition("artifactGroupId", "POC_DENY_SJ_UPDATE").deleteAll()
        ec.entity.find("moqui.security.ArtifactGroup").condition("artifactGroupId", "POC_DENY_SJ_UPDATE").deleteAll()
        ec.artifactExecution.enableAuthz()
    }

    def "5 approval email goes to the other approver and not the requester"() {
        when:
        // tx-commit SECAs run asynchronously after the commit, so give the worker thread a moment
        def emails = []
        for (int i = 0; i < 50 && !emails; i++) {
            emails = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailTemplateId", "CCR_APPROVAL_REQUEST").disableAuthz().useCache(false).list()
            if (!emails) Thread.sleep(100)
        }
        def forJ1 = emails.findAll { it.subject?.contains(ccrs("poc_j1")[0].requestId) }
        then:
        forJ1.size() == 1
        forJ1[0].toAddresses.contains("bob@example.com")
        forJ1[0].toAddresses.contains("dave@example.com")
        !forJ1[0].toAddresses.contains("alice@example.com")
        forJ1[0].subject.startsWith("Approval needed: Pause job poc_j1")
    }

    def "5b approval email body renders the summary, reason and review link"() {
        // EmailMessage.body is only stored when an SMTP host is set, so render the template screen directly
        when:
        ec.context.push([requestId: "100000", summaryText: "Pause job poc_j1", reason: "reindex running", reasonMissing: "N",
                statusId: "CcrPending", statusDescription: "Pending approval", requesterName: "Alice", deciderName: null, decisionComment: null,
                errorText: null, plannedFixDate: null, expireDate: null, reviewUrl: "https://oms.example.com/apps/Oms/ChangeApproval/Request?requestId=100000",
                diffList: [[field: "paused", oldValue: "N", newValue: "Y"]]])
        String html
        try { html = ec.screen.makeRender().rootScreen("component://config-approval/screen/email/ApprovalRequest.xml").renderMode("html").render() }
        finally { ec.context.pop() }
        then:
        html.contains("Pause job poc_j1")
        html.contains("reindex running")
        html.contains("/apps/Oms/ChangeApproval/Request?requestId=100000")
    }

    def "6 requester and non-approvers cannot approve"() {
        given:
        String rid = ccrs("poc_j1")[0].requestId
        when:
        ec.message.clearAll()
        as_("alice") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: rid]).call() }
        def e1 = ec.message.errorsString
        ec.message.clearAll()
        as_("carol") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: rid]).call() }
        def e2 = ec.message.errorsString
        ec.message.clearAll()
        then:
        e1.contains("not an approver")
        e2.contains("not an approver")
        ccrs("poc_j1")[0].statusId == "CcrPending"
        job("poc_j1").paused == "N"
    }

    def "7 approver approves: change is replayed, status Applied, requester is told"() {
        given:
        String rid = ccrs("poc_j1")[0].requestId
        when:
        ec.message.clearAll()
        as_("bob") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: rid, decisionComment: "ok"]).call() }
        def errs = ec.message.errors
        def ccr = ec.entity.find(CCR).condition("requestId", rid).disableAuthz().useCache(false).one()
        def decisionMail = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailTemplateId", "CCR_DECISION").disableAuthz().useCache(false).list().findAll { it.subject.contains(rid) }
        then:
        errs.isEmpty()
        ccr.statusId == "CcrApplied"
        ccr.decidedByUserId == "poc_bob"
        ccr.appliedDate != null
        job("poc_j1").paused == "Y"
        // only one request row: the replay was not gated again
        ccrs("poc_j1").size() == 1
        decisionMail.size() == 1
        decisionMail[0].toAddresses == "alice@example.com"
        decisionMail[0].subject.startsWith("Applied:")
    }

    def "8 reject needs a comment and leaves the job alone"() {
        given:
        makeJob("poc_j8")
        callUpdate("alice", [jobName: "poc_j8", paused: "Y"])
        String rid = ccrs("poc_j8")[0].requestId
        when:
        ec.message.clearAll()
        as_("bob") { ec.service.sync().name(SVC + "reject#ConfigChangeRequest").parameters([requestId: rid]).call() }
        def noComment = ec.message.hasError()
        ec.message.clearAll()
        as_("bob") { ec.service.sync().name(SVC + "reject#ConfigChangeRequest").parameters([requestId: rid, decisionComment: "not now"]).call() }
        then:
        noComment
        ccrs("poc_j8")[0].statusId == "CcrRejected"
        ccrs("poc_j8")[0].reasonMissing == "Y"
        job("poc_j8").paused == "N"
    }

    def "9 stale check: record changed after the request makes approval fail with a clear message"() {
        given:
        makeJob("poc_j9")
        callUpdate("alice", [jobName: "poc_j9", paused: "Y", _changeReason: "r"])
        String rid = ccrs("poc_j9")[0].requestId
        // someone with no gate (system) pauses it meanwhile
        ec.artifactExecution.disableAuthz()
        ec.service.sync().name("update#moqui.service.job.ServiceJob").parameters([jobName: "poc_j9", paused: "Y"]).call()
        ec.artifactExecution.enableAuthz()
        when:
        ec.message.clearAll()
        as_("bob") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: rid]).call() }
        def ccr = ccrs("poc_j9")[0]
        then:
        ccr.statusId == "CcrFailed"
        ccr.errorText.contains("Record changed since the request")
        !ec.message.hasError()
    }

    def "10 a newer request for the same field supersedes the older pending one"() {
        given: makeJob("poc_j10")
        when:
        callUpdate("alice", [jobName: "poc_j10", paused: "Y", _changeReason: "first"])
        callUpdate("alice", [jobName: "poc_j10", paused: "Y", _changeReason: "second"])
        def reqs = ccrs("poc_j10")
        then:
        reqs.size() == 2
        reqs[0].statusId == "CcrCancelled"
        reqs[0].supersededById == reqs[1].requestId
        reqs[1].statusId == "CcrPending"
    }

    def "11 requester can cancel own pending request, others cannot"() {
        given:
        String rid = ccrs("poc_j10")[1].requestId
        when:
        ec.message.clearAll()
        as_("carol") { ec.service.sync().name(SVC + "cancel#ConfigChangeRequest").parameters([requestId: rid]).call() }
        def byOther = ec.message.hasError()
        ec.message.clearAll()
        as_("alice") { ec.service.sync().name(SVC + "cancel#ConfigChangeRequest").parameters([requestId: rid]).call() }
        then:
        byOther
        ccrs("poc_j10")[1].statusId == "CcrCancelled"
    }

    def "12 secrets: parametersJson and originalJson are stored encrypted, diff is readable"() {
        when:
        def rid = ccrs("poc_j9")[0].requestId
        def row = ec.entity.getEntityDefinition(CCR).getFieldInfo("parametersJson")
        then:
        row.encrypt
        ec.entity.getEntityDefinition(CCR).getFieldInfo("originalJson").encrypt
        !ec.entity.getEntityDefinition(CCR).getFieldInfo("diffJson").encrypt
    }

    def "13 wrapper service JobServices.update#ServiceJob (REST PUT) is gated once, with the reason, and replays through the wrapper"() {
        given: makeJob("poc_j13")
        when:
        ec.message.clearAll()
        as_("alice") { ec.service.sync().name("co.hotwax.job.JobServices.update#ServiceJob")
                .parameters([jobName: "poc_j13", paused: "Y", description: "via wrapper", _changeReason: "migration freeze"]).call() }
        def reqs = ccrs("poc_j13")
        def errs = ec.message.errors
        then:
        errs.isEmpty()
        job("poc_j13").paused == "N"
        job("poc_j13").description == "via wrapper"
        reqs.size() == 1
        reqs[0].reason == "migration freeze"
        reqs[0].reasonMissing == "N"
        reqs[0].serviceName == "co.hotwax.job.JobServices.update#ServiceJob"
        reqs[0].summaryText == "Pause job poc_j13"

        when:
        as_("bob") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: reqs[0].requestId]).call() }
        then:
        ec.entity.find(CCR).condition("requestId", reqs[0].requestId).disableAuthz().useCache(false).one().statusId == "CcrApplied"
        job("poc_j13").paused == "Y"
        ccrs("poc_j13").size() == 1
    }

    def "14 MCP update_service_job: the tool declares the reason params and a call shaped like the tool is held with its reason"() {
        given:
        makeJob("poc_j14")
        // the runtime's component/config-approval is a symlink into the real components dir, which also holds oms
        File compDir = new File(System.getProperty("moqui.runtime"), "component/config-approval").canonicalFile.parentFile
        def mcp = new groovy.xml.XmlSlurper().parse(new File(compDir, "oms/service/oms.mcp.xml"))
        def tool = mcp.tool.find { it.@name == "update_service_job" }
        def declared = tool.parameter.collect { it.@name.text() }
        when:
        // the MCP bridge calls the declared service with exactly the tool's declared parameters
        as_("alice") { ec.service.sync().name(tool.service.@name.text())
                .parameters([jobName: "poc_j14", paused: "Y", _changeReason: "agent pause for reindex"]).call() }
        def reqs = ccrs("poc_j14")
        then:
        declared.containsAll(["jobName", "paused", "_changeReason", "_plannedFixDate"])
        job("poc_j14").paused == "N"
        reqs.size() == 1
        reqs[0].reason == "agent pause for reindex"
    }

    def "15 REST response carries X-Config-Change-Request when a change is held"() {
        given:
        makeJob("poc_j15")
        Map headers = [:]
        def resp = [addHeader: { String k, String v -> headers[k] = v }, setHeader: { String k, String v -> headers[k] = v }] as jakarta.servlet.http.HttpServletResponse
        def eci = (org.moqui.impl.context.ExecutionContextImpl) ec
        // log in before the stub goes in: login/logout touch the real web session
        ec.user.internalLoginUser("poc_alice")
        eci.setWebFacade([getResponse: { -> resp }, getRequestParameters: { -> [:] }] as org.moqui.context.WebFacade)
        when:
        try { ec.service.sync().name("update#moqui.service.job.ServiceJob").parameters([jobName: "poc_j15", paused: "Y", _changeReason: "rest test"]).call() }
        finally { eci.@webFacade = null; ec.user.logoutUser() }
        def reqs = ccrs("poc_j15")
        then:
        reqs.size() == 1
        reqs[0].requestChannel == "rest"
        headers["X-Config-Change-Request"] == reqs[0].requestId
    }

    def "16 admin screens render: list shows the pending request, detail shows the diff and offers decisions to an approver only"() {
        given:
        makeJob("poc_j16")
        ec.user.internalLoginUser("poc_alice")
        try { ec.service.sync().name("update#moqui.service.job.ServiceJob").parameters([jobName: "poc_j16", paused: "Y", _changeReason: "screen test"]).call() }
        finally { ec.user.logoutUser() }
        String rid = ccrs("poc_j16")[0].requestId
        def render = { String loc, String user, Map params ->
            ec.user.internalLoginUser(user)
            ec.context.push(params)
            try { return ec.screen.makeRender().rootScreen(loc).renderMode("html").render() }
            finally { ec.context.pop(); ec.user.logoutUser() }
        }
        when:
        String list = render("component://config-approval/screen/ChangeApproval/FindRequest.xml", "poc_bob", [:])
        String asApprover = render("component://config-approval/screen/ChangeApproval/Request.xml", "poc_bob", [requestId: rid])
        String asMaker = render("component://config-approval/screen/ChangeApproval/Request.xml", "poc_alice", [requestId: rid])
        then:
        list.contains(rid)
        list.contains("poc_j16")
        asApprover.contains("screen test")
        asApprover.contains("paused")
        asApprover.contains("ApproveDialog")
        asApprover.contains("RejectDialog")
        !asApprover.contains("CancelForm")
        !asMaker.contains("ApproveDialog")
        asMaker.contains("CancelForm")
    }

    def "17 digest and list services (MCP read tools): waiting and forgotten pauses show up, secrets never do"() {
        given:
        makeJob("poc_j17a"); makeJob("poc_j17b")
        // a: approved pause whose planned fix date has passed -> forgotten; b: still waiting
        callUpdate("alice", [jobName: "poc_j17a", paused: "Y", _changeReason: "migration", _plannedFixDate: "2026-10-01 10:00:00"])
        String ridA = ccrs("poc_j17a")[0].requestId
        as_("bob") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: ridA]).call() }
        callUpdate("alice", [jobName: "poc_j17b", paused: "Y", _changeReason: "upgrade"])
        String ridB = ccrs("poc_j17b")[0].requestId
        when:
        Map digest = as_("alice") { ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalDigestServices.get#ConfigChangeDigest").parameters([hours: 48]).call() }
        Map pendingList = as_("alice") { ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalDigestServices.get#ConfigChangeRequests").parameters([statusId: "CcrPending"]).call() }
        def tools = new groovy.xml.XmlSlurper().parse(new File("component/config-approval/service/config-approval.mcp.xml")).tool.collect { it.@name.text() }
        then:
        digest.digest.waitingForApproval.any { it.requestId == ridB }
        digest.digest.possiblyForgottenPauses.any { it.jobName == "poc_j17a" && it.why.contains("planned fix date") }
        !digest.digest.possiblyForgottenPauses.any { it.jobName == "poc_j17b" }
        digest.digestText.contains("CHECK: job poc_j17a")
        digest.digestText.contains("WAITING")
        pendingList.requestList.any { it.requestId == ridB && it.reason == "upgrade" && it.changes[0].field == "paused" }
        !pendingList.requestList.any { it.containsKey("parametersJson") || it.containsKey("originalJson") }
        tools.sort() == ["get_config_change_digest", "get_config_change_requests"]
    }

    def "18 a direct entity write to a guarded field (no service gate) is recorded as ungated and shows in the digest"() {
        given:
        makeJob("poc_j18")
        when:
        as_("alice") {
            ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "poc_j18").useCache(false).one().set("paused", "Y").update(); [:]
        }
        // a direct write that changes only an unguarded field records nothing
        as_("alice") {
            ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "poc_j18").useCache(false).one().set("description", "x").update(); [:]
        }
        def reqs = ccrs("poc_j18")
        Map digest = as_("alice") { ec.service.sync().name("co.hotwax.configapproval.ConfigApprovalDigestServices.get#ConfigChangeDigest").parameters([hours: 48]).call() }
        then:
        job("poc_j18").paused == "Y"
        reqs.size() == 1
        reqs[0].changeSourceEnumId == "CcsUngated"
        reqs[0].statusId == "CcrApplied"
        reqs[0].requestedByUserId == "poc_alice"
        reqs[0].diffJson.contains('"newValue":"Y"')
        digest.digest.ungatedChanges.any { it.requestId == reqs[0].requestId }
        digest.digestText.contains("UNAPPROVED")
    }

    def "19 sweep: an old pending request gets one reminder, an overdue one expires and the job stays untouched"() {
        given:
        makeJob("poc_j19a"); makeJob("poc_j19b")
        callUpdate("alice", [jobName: "poc_j19a", paused: "Y", _changeReason: "r1"])
        callUpdate("alice", [jobName: "poc_j19b", paused: "Y", _changeReason: "r2"])
        String ridA = ccrs("poc_j19a")[0].requestId, ridB = ccrs("poc_j19b")[0].requestId
        ec.artifactExecution.disableAuthz()
        long hrs = 3600000L, now = System.currentTimeMillis()
        // a: waiting 30h (past the 24h reminder, not expired); b: past its expire date
        ec.entity.find(CCR).condition("requestId", ridA).one().set("requestedDate", new Timestamp(now - 30 * hrs)).update()
        ec.entity.find(CCR).condition("requestId", ridB).one().set("expireDate", new Timestamp(now - hrs)).update()
        ec.artifactExecution.enableAuthz()
        when:
        Map r1 = ec.service.sync().name(SVC + "run#ConfigApprovalSweep").call()
        Map r2 = ec.service.sync().name(SVC + "run#ConfigApprovalSweep").call()   // second run must not remind again
        def a = ccrs("poc_j19a")[0], b = ccrs("poc_j19b")[0]
        then:
        r1.expiredCount >= 1 && r1.remindedCount >= 1
        r2.expiredCount == 0 && r2.remindedCount == 0
        a.statusId == "CcrPending" && a.reminderDate != null
        b.statusId == "CcrExpired"
        job("poc_j19a").paused == "N" && job("poc_j19b").paused == "N"
        // an expired request can no longer be approved
        when:
        ec.message.clearAll()
        as_("bob") { ec.service.sync().name(SVC + "approve#ConfigChangeRequest").parameters([requestId: ridB]).call() }
        then:
        ec.message.hasError()
        job("poc_j19b").paused == "N"
        cleanup:
        ec.message.clearAll()
    }
}
