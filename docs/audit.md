# Audit history

Interactive administrators can open **Dashboard → Audit** (`/dashboard/audit`) or call `GET /api/v1/audit`. Ordinary members and service/CI keys cannot read the journal, including administrator-owned service keys. Authentication is revalidated against shared MySQL state on every read.

## Records and pagination

Each event contains only `id`, `actorId`, `action`, `subjectId` and `occurredAt` (Unix milliseconds from the database clock). Actor and subject are stable resource identifiers. Trusted server operations use actor `system`. There are no credential values, request bodies, prompts, response bodies, provider account email addresses or before/after configuration values in these records. The browser escapes every text field and API/browser responses use `Cache-Control: no-store`.

The API returns at most 50 events, newest identifier first. Response shape (event list abbreviated):

```json
{
  "events": [
    {
      "id": 100,
      "actorId": "member-uuid",
      "action": "account.enrolled",
      "subjectId": "account-uuid",
      "occurredAt": 1792416000000
    }
  ],
  "nextBeforeId": 100
}
```

`nextBeforeId` is present only when an older page exists; otherwise it is null or omitted. Pass it to `GET /api/v1/audit?before=100`. The typed SDK exposes `audit(beforeId = null)`. Cursors must be single positive 64-bit integers; there is no unbounded page-size parameter or offset scan. A `(team_id, id)` index supports the team-scoped range query. New events do not shift an already-read page; refresh the latest page to see events committed while browsing. The journal is not a consistent-snapshot export across pages.

## Current coverage

- First administrator creation (`member.bootstrap`), successful login/logout, password replacement/reset, member creation and access changes.
- Device registration/rename/revocation and explicit authentication-session revocation.
- Service/CI credential creation and revocation, including self-revocation through logout.
- Account enrollment and credential replacement, operator metadata configuration, access-only activation and refresh-ownership handover.
- Member allowance/concurrency/weight policy, pool budgets, model/effort/experimental settings and routing rule changes.

Account writes and their audit event commit in the same team-locked transaction. The write names either an authenticated principal or an explicit trusted system operation. Current interactive administrator permissions are checked again after provider identity verification and before any write or idempotency replay is returned. Revocation during a remote verification therefore prevents the later mutation. Repeating the same enrollment operation does not append a duplicate event; conflicting or denied writes append none. Repeating an unchanged handover state also appends none.

This is an administrative/authentication journal, not the inference request ledger. Existing request/settlement metadata and directive receipts remain separate. Automatic refresh, every failed login and every automatic session expiry/rotation are not individually journaled here. Cascading session revocations are represented by their triggering password/member/device event. Group/role expansion, agent-run administration, retention/export and operational audit coverage remain V1 acceptance work; this page does not claim a complete compliance archive.

## Reference and verification

TeamClaude's `src/config-ops.js`, `test/import-credentials.test.js` and `test/server-account-controls.test.js` remain the reference for account metadata controls and local credential import. TeamCode retains verified identity-based updates and metadata preservation while adding authenticated team attribution, shared transactions and cross-replica revocation checks. TeamClaude's local configuration hooks do not provide this shared team journal.

`AccountAuditPersistenceTest` uses real MySQL and two independent pools to exercise enrollment/reimport/idempotency, metadata/handover events and revocation during delayed provider verification. `AuditPersistenceTest` checks bounded pagination across concurrent appends, team isolation, current role/scope/expiry checks and restart-compatible migrations. `AuditRoutesTest` covers the typed SDK, administrator-only API/dashboard access, malformed cursors, escaped content and private-cache headers. The packaged-server/native-team smoke additionally checks journal persistence across server restart and native logout. These fixtures make no real provider requests.
