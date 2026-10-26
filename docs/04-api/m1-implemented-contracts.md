# M1 REST / RPC contract delta (source implemented, build pending)

Scope: M1 foundation only. This file distinguishes `flow-api` implemented Java controllers from the proposed full REST API in [rest-api.md](rest-api.md).

## Implemented Java source endpoints

| Method | Path | Authentication | Request | Response |
|---|---|---|---|---|
| GET | `/api/v1/auth/csrf` | anonymous | none | `headerName`, `token` JSON + HttpOnly CSRF cookie |
| POST | `/api/v1/auth/login` | anonymous + CSRF | `username`, `password` | `{username,role}` + server session |
| POST | `/api/v1/auth/logout` | session + CSRF | none | 204 + invalidated session |
| GET | `/api/v1/auth/me` | session | none | `{username,role}` |
| GET | `/api/v1/projects` | session | none | first 100 owner projects |
| POST | `/api/v1/projects` | ADMIN + CSRF | `Idempotency-Key`, `{name}` | 201 created, 200 same-key repeat, 409 same-key/different-name |
| GET | `/actuator/health` | anonymous | none | Spring Boot health |

REST GET projects uses the *authenticated session principal*, not a caller-provided owner username. M1 project scope is the owning user, not the complete M2+ project membership/RBAC model.

M1 source contract: `com.peachsoft.otryx.flow.contracts.v1.ProjectRpc` (OTRYX `@OtryxRpcContract`). `listByOwner(String)` and `create(ProjectCreateCommand)` use `@OtryxRpcIdempotent`; the latter is safe to replay only with a stable `(ownerUsername, requestKey)` pair. Project DTOs are Java Serializable records. The `flow-metadata` provider is annotated `@OtryxRpcService(..., version="1.0.0")`; flow-api injects `@OtryxRpcReference(version="1.0.0")`.

## Failure and consistency
- HTTP session authentication is limited to one `flow-api` instance in M1; shared session storage is deferred.
- MySQL has `UNIQUE(owner_username,request_key)` and first-write response lookup; same-key different-name returns application conflict. No cross-database transaction exists.
- `RPC_UNAVAILABLE` maps to HTTP 503; provider exception maps to a sanitized HTTP 502.
- The initial admin is bootstrapped once from environment, with BCrypt hashing. Password rotation and full project-level RBAC are future milestones.
- Caller identity at the RPC layer in M1 is trusted network development only; production mTLS + allowlist application authorization is required by M4.
- Session cookies should be `Secure` under HTTPS; M1 is loopback HTTP only.
- No OTRYX build result or E2E response is claimed until the checks in [M1 validation report](../06-testing/m1-validation-report.md) are executed.

M1 hardening: unauthenticated REST requests explicitly return HTTP **401**, while invalid CSRF tokens and authenticated permission denials return **403**. The frontend reads the JSON response to obtain the CSRF header value; the CSRF cookie itself remains HttpOnly. These are source-level contracts awaiting Spring integration execution.
