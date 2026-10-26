# M1 developer Compose (implementation candidate)

M1 ships a developer-only HTTP site at `http://127.0.0.1:8088`. It is NOT an HTTPS production website; the RPC network is `TRUSTED_DEVELOPMENT` and the Etcd channel has no mTLS. Do not publish ports to the Internet.

## Prerequisites
- Docker Engine with Compose V2, Internet access for initial image, Maven and npm downloads.
- Enough RAM and free disk for building the entire pinned OTRYX RPC source tree.
- Java build targets JDK 21; runtime image uses JDK 21.
- `OTRYX_RPC_SHA` is the exact commit `706b6506e49e1ade70de9c6c64c844fdd4631e17` verified from `main` on 2026-10-26. The SHA is an implementation dependency, **not a test pass claim**.

## Commands
Run from repository root:

```bash
cp .env.example .env
# Edit FLOW_DB_ROOT_PASSWORD, FLOW_DB_PASSWORD and FLOW_ADMIN_PASSWORD to strong secrets.
docker compose build
bash scripts/start_dev_compose.sh
docker compose ps
# Browser: http://127.0.0.1:8088
set -a; . ./.env; set +a
bash scripts/test_m1_e2e.sh
```

Backend Dockerfile is built once by `flow-api` and reused as the `flow-metadata` image. It builds OTRYX RPC from the exact pinned upstream source SHA into the Maven build stage, then builds both `flow-api` and `flow-metadata`. `flow-api` calls the metadata provider **via real OTRYX RPC**, discovered through Etcd. No HTTP fallback exists between the services.

## Current scope
- API login: server session, BCrypt bootstrap admin, CSRF for state-changing operations.
- Identity owner: `flow_identity` schema, `flow_user` table. Metadata owner: `flow_metadata` schema, `flow_project` table.
- `POST /api/v1/projects` requires a stable `Idempotency-Key`, and returning 200 on same-key retry is expected.
- M1 contains no live scheduler, Worker, DAG engine, alerting or HTTPS certificate manager. The UI marks these modules as planned rather than showing fake metrics.
- The Compose MySQL account has grants on two schemas for local convenience; **production must use one least-privileged DB user per service**.
- Session storage is local to one flow-api process. Horizontal API replicas require clustered session storage in a later milestone.
- First use of network dependencies, JDK/Maven build and runtime E2E were not executed in the offline authoring environment. Execute commands above and attach raw logs before marking FLOW-003 through FLOW-007 done.

## Troubleshooting
1. If the initial project query returns 503, check `docker compose logs flow-metadata`, then check Etcd readiness and local application-directory convergence; retry after startup.
2. If login fails, verify `.env`, Flyway's `flow_identity` migration and `flow_user` initialization. Initial admin password is not automatically rotated when a user already exists.
3. If Docker build fails to resolve `otryx-codegen` or `otryx-spring-boot-starter`, verify that pinned RPC `mvn install` succeeded and the Maven repository path was retained within the builder stage.
4. If CSRF returns 403, first GET `/api/v1/auth/csrf` with the same cookies, and supply the returned token as `X-XSRF-TOKEN`.
5. Never remove MySQL volumes to "fix" migrations without explicit backup and data-loss approval.

## M1 offline checks and manual CI integration

Before pulling dependencies or starting containers, run from repository root:

```bash
bash scripts/verify_m1_offline.sh
```

This validates documentation, static contracts, a Node HTTP-client test suite, E2E shell script behavior against a **fake local HTTP server**, and Java DTO syntax. It does **not** launch Spring, Vue, Etcd, MySQL or real OTRYX RPC. A separately configured networked machine must run the full Maven/Vue builds and real Compose E2E.

The GitHub Actions file `.github/workflows/m1-integration.yml` is **manual-only** (`workflow_dispatch`) and builds pinned OTRYX modules, starts Compose and executes the full E2E with a metadata-service restart. It is separate from fast PR source/compile checks to limit unnecessary CI load.

On a local Docker machine, run the additional fault-injection path explicitly:

```bash
set -a; source .env; set +a
FLOW_E2E_RESTART_METADATA=1 bash scripts/test_m1_e2e.sh
```

This test **restarts `flow-metadata`**; it must not run on production or on a shared environment unless interruption is explicitly approved. Developer Compose binds the web port to `127.0.0.1` and uses an internal-only container network; it is not a public demo deployment.

## Etcd endpoint URI requirement

The OTRYX Etcd Registry adapter validates complete URLs. Set `OTRYX_REGISTRY_ENDPOINTS=http://etcd:2379` inside the isolated Compose network, or `http://127.0.0.1:2379` for a matching local Etcd endpoint. A bare `etcd:2379` URI is invalid and is rejected with `Registry security rejected: UNSUPPORTED_PROTECTION`. This is strictly a TRUSTED_DEVELOPMENT configuration; production requires protected Etcd and NETWORK_MTLS.

## Docker internal networks and loopback ingress

The `internal` network is exclusively for the Java RPC, MySQL and Etcd containers. A second `ingress` bridge is attached **only** to Nginx (`flow-web`) so Docker can publish `127.0.0.1:8088:80` to the host. Do not attach the RPC/DB services to the ingress bridge or bind any port to `0.0.0.0` for this development profile. The initial all-internal network prevented host loopback connections during GitHub Actions E2E.

## M2 OTRYX RPC application membership startup

多个 Java Consumer/Provider 与 Etcd 同时启动时，应用成员确认有可能超出 `otryx.rpc.registry.control-plane-timeout`，导致启动时 `Failed to initialize @OtryxRpcReference`，并令 Nginx 暂时返回 502。这不是业务 RPC 可以静默忽略的错误。请使用根目录 `bash scripts/start_dev_compose.sh` 按 MySQL/Etcd → Metadata → Scheduler×2 → Dispatcher×2 → Engine → API → Web 的依赖顺序启动，并等待各 Java 应用输出 `Started Flow*Application` 后再推进。脚本对每个 JVM 有明确超时和退出检测。开发 Compose 为 Engine/API 配置有限次 `on-failure` 重启作为兜底，不能替代对真实门禁的验收。

`flow-dispatcher` 内的 `/_e2e/dispatch/*` 只有在 `FLOW_DISPATCH_E2E_PROFILE=e2e` 时注册；默认不可用且端口未映射到宿主机。GitHub Actions 会在隔离网络中临时启用，用于租约 Fencing 故障注入。严禁在生产启用此 Profile。
