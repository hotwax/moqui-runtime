# config-approval

Two-step (maker-checker) approval for sensitive config changes. A guarded change is held, approvers get an email, and the change
is applied when one of them approves. No change to the framework, runtime or other components.

## Install
1. Clone this component into `runtime/component/config-approval` (it depends on `oms` and `maarg-util`).
2. Load the seed data: `java -jar moqui.war load types=seed,seed-initial` (loads `data/ConfigApprovalSeedData.xml`: the
   CONFIG_APPROVERS group, authz, guard fields, email templates). Entities are created on startup.
3. Put at least **two** users in the `CONFIG_APPROVERS` group, each with an email address (Change Approval > Setup does this).
   In OMS, Setup also mirrors the membership as an OFBiz `CONFIG_APPROVERS` security group (plus a `MaargSecGrp` mapping), because
   maarg-util's login sync expires any Moqui group that has no OFBiz group behind it. Add approvers through Setup, not by SQL.
   This mirror applies only to users that have an OFBiz `UserLogin`; in an OFBiz-independent (`omssetup -PmaargOnly=true`) setup
   Moqui `UserAccount` is the only login and nothing is mirrored.
   Nobody can approve their own request, so a lone approver leaves their own requests waiting; Setup warns about this.
4. Mail: the templates use email server `SYSTEM`; configure SMTP for it. Review links use the `webapp_http_host` property
   (and `webapp_https_enabled`), so set it to the public host.
5. Optional properties (MoquiConf.xml): `config.approval.enabled` (master switch), `config.approval.expire.hours` (72),
   `config.approval.reminder.hours` (24), `config.approval.breakglass.restrict`.

## Guarding
- Which *fields* are guarded is data: Change Approval > Setup (Enumeration type `ConfigApprovalField`, code `Entity.field`).
- Which *entities/services* are gated is code: `service/config-approval.secas.xml`. Today: ServiceJob, ServiceJobParameter,
  SystemMessageRemote (update/store, plus create/delete) and the maarg-util job wrapper. To gate another entity, add its
  SECAs there (copy the ServiceJobParameter ones: hold, record, and for create/delete the HeldMsg rule), add the field codes in
  Setup, and add authz for the screens' users.
- Setup tells you when code is still missing. The Guarded fields table has a "Hooks in code" column: **Yes** means the entity has
  update/store SECAs in `config-approval.secas.xml`; **NO: developer must add SECAs** means the field is only listed, not gated.
  Adding a field of an unhooked entity also shows a "Developer step needed" message, and a hint under the table points here.
  The check only looks for `update#`/`store#` SECAs of the entity, so it says nothing about whether create/delete are hooked.

## Use
- Makers: normal screens/APIs. A held change returns a "request sent" message; **New request** (Change Approval menu) takes a
  reason and an optional break-glass. The stock Tools forms have no reason box: add it afterwards on the request page.
- Approvers: Change Approval > Requests > Approve/Reject. Break-glass applies at once and is flagged in the digest.
- Digest/read tools for the AI summary: `config-approval.mcp.xml` (needs moqui-ai).

## Tests
`src/test/groovy/ConfigApprovalPocTests.groovy` (Spock, 44 tests) runs against an isolated H2 runtime.
