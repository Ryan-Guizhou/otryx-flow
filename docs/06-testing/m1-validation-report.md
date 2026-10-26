> **Current verified M1 gate:** GitHub PR [#2](https://github.com/Ryan-Guizhou/otryx-flow/pull/2) merged to `main@3291adcbd43102b9a46772480d80ee5055ebea63`. Exact PR Head `6377e77dd908264c946fbe440d4cc4e86cb78014` passed `docs`, `source`, `backend`, `frontend`, and real Compose integration (run [40279550912](https://github.com/Ryan-Guizhou/otryx-flow/actions/runs/40279550912), MySQL + Etcd + JVM-to-JVM RPC, session/CSRF, idempotence and restart). Historical offline observations below are retained as historical evidence, not current incomplete state.

# M1 source and integration validation report

**Date:** 2026-10-26. **Status:** local source candidate; release and site deployment blocked.

## Verified in current environment

| Check | Result | Evidence/limitations |
|---|---|---|
| Existing M0 document links and FR/NFR/AT mapping | PASS | `python3 scripts/check_docs.py`; [local offline raw output](m1-offline-evidence.txt) |
| M1 repository files, annotations, SQL and security guards | PASS | `python3 scripts/check_m1_source.py`; static assertions only |
| TypeScript HTTP client file syntax/typecheck | PASS | `tsc --noEmit --target ES2022 --lib ES2022,DOM --module ESNext --moduleResolution bundler flow-web/src/api.ts` |
| Java 21 RPC DTO record compilation | PASS | `javac` compiled ProjectView, ProjectListReply, ProjectCreateCommand, ProjectCreatedReply (without external OTRYX annotations) |
| YAML + shell syntax validation | PASS | Parsed Compose and Actions YAML with PyYAML; `bash -n scripts/test_m1_e2e.sh` |
| Vue SFC script-setup TS syntax/typecheck (offline shim) | PASS | Global `tsc` checked extracted `App.vue` script against a minimal local Vue declaration; Vue template compilation is NOT covered |
| Node REST API-client executable contract tests | PASS | `node --experimental-strip-types --test scripts/test_frontend_api.mjs`: 3 passed (fetch mock, not browser) |
| E2E shell script against synthetic local HTTP server | PASS | `node scripts/test_e2e_harness_offline.mjs`; no Java, MySQL, Etcd or OTRYX RPC is exercised |
| Authentication/session and bootstrap-admin JUnit source | AUTHORED, NOT RUN | Requires Maven dependency resolution and Java test execution |
| Whole front-end Vue build | NOT RUN | No npm dependencies installed, no network to download them |
| Maven compile/test | NOT RUN | Maven unavailable, Maven Central unreachable |
| Docker Compose build/start | NOT RUN | Container Docker Engine unavailable and images cannot be downloaded |
| Etcd application registration and true two-JVM RPC | NOT RUN | Requires compiled OTRYX RPC, Etcd, MySQL and two JVM processes |
| Browser login + create project integration | NOT RUN | Execute `scripts/test_m1_e2e.sh` after healthy Compose startup |
| Public HTTPS deployment, mTLS and production auth | NOT RUN | M4/M6 security and deployment milestones |
| GitHub PR / CI | NOT RUN | Repository newly created; actual CI not yet executed |

## Build and E2E commands (planned; NOT run here)

Run from repository root on a networked machine:

```bash
cp .env.example .env
# Replace all credential placeholders with strong unique secrets.
docker compose build
docker compose up -d
set -a; . ./.env; set +a
bash scripts/test_m1_e2e.sh
```

The E2E script checks unauthenticated 401, CSRF bootstrap, login, RPC metadata listing, persisted create, replay, conflict 409, invalid idempotency key 400, CSRF rejection 403 and logout invalidation. With `FLOW_E2E_RESTART_METADATA=1`, it additionally restarts the metadata service and verifies persisted project visibility after rediscovery. This is the first acceptance slice for AT-01/02/03, but none are marked PASS until the test runs with captured logs.

## Available automated integration workflow

- `.github/workflows/m1-quality.yml`: changed-source PR checks, including targeted Java/Maven modules and Node API tests.
- `.github/workflows/m1-integration.yml`: **manual dispatch only**. Builds Docker images from the pinned OTRYX commit, creates random CI-only credentials, executes the real Compose E2E with a metadata restart, and tears down ephemeral volumes.
- Tests and workflow declarations have been authored, **not run on GitHub** until the first PR is pushed and workflows run.
- The development Compose network now blocks runtime external egress; public network exposure remains prohibited.

## Known blockers and next work

1. Networked build host must validate the pinned RPC commit and the generated-code contract.
2. Audit and strengthen pre-release security: login rate limits, secure HTTPS cookies, per-service DB credentials, mTLS/authorizer and shared session store.
3. Commit frontend lockfile after verified `npm install` and adopt `npm ci` for reproducible installs.
4. Move from source candidate to completed M1 only after GitHub Head checks, Compose E2E, and browser verification all pass.
