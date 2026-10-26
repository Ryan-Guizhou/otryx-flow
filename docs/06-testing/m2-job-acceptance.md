# FLOW-008 M2 JobDefinition acceptance gate

Status: source candidate; no success claim before the exact PR-head Java + Vue + real Compose workflows are green.

## Traceability

- Requirements: FR-004, FR-022 (project ownership for job changes), AT-03 and AT-14 (partial API/compatibility coverage).
- Schema migration: `flow-metadata/src/main/resources/db/migration/V2__create_job_definition.sql`.
- Java unit tests: `JobRpcServiceTest` and `JobControllerTest`.
- Frontend Node tests: `scripts/test_frontend_api.mjs`.
- Real E2E: `scripts/test_m1_e2e.sh` invokes `scripts/test_m2_job_e2e.sh` when `FLOW_E2E_M2_JOBS=1`.

## Mandatory checks

| Test | Pass condition |
|---|---|
| Create draft | HTTP 201, job belongs to a project controlled by the session owner |
| Idempotent retry | Same key and same body -> HTTP 200, same job UUID |
| Conflict | Same key but different body -> HTTP 409, no second logical job |
| Publication | Same stable publish key -> one immutable revision and repeatable response |
| CAS | Edit with current version succeeds, concurrent stale version -> 409 |
| Immutable history | Revision 1 remains unchanged after draft edit and publication of revision 2 |
| Security | Missing session is 401, invalid CSRF is 403, other project/owner is not leaked |
| Recovery | Metadata restart leaves both published revisions queryable through the same RPC path |
| Resource bounds | JSON object <=16KiB, trusted handler key, timeout/attempt bounds enforced |
| CI evidence | Exact Head SHA, test reports and logs confirm above; no simulated RPC acceptance |

## Scope exclusions

This feature does not execute or schedule a Handler. Scheduler fire points, misfire policy, Worker leases and Outbox/inbox are tracked by Issues #4-#9. The web UI must not imply that saving or publishing starts execution.
