# OTRYX Flow M2 开发部署（唯一 Nacos 3.2.4）

> **迁移候选，需 Issue #15 最新 PR CI 通过。** 仅供本地与隔离 CI 使用，当前还没有生产可用的 HTTPS/Nacos mTLS 方案。

## 前置条件

- Docker Engine + Compose V2、可以拉取 `nacos/nacos-server:v3.2.4` 和 Maven/npm 依赖。
- Docker 宿主机为后端编译和 Nacos 3.2.4 准备足够的内存/磁盘。Java 构建和运行使用 JDK 21。
- `OTRYX_RPC_SHA=706b6506e49e1ade70de9c6c64c844fdd4631e17` 通过完整源码固定，不发布到 Maven Central。

## 从仓库根目录启动

```bash
cp .env.example .env
# 替换所有 REPLACE_WITH 占位：数据库、管理员、Nacos signing token/identity
docker compose build
bash scripts/start_dev_compose.sh
docker compose ps
# 浏览器访问 http://127.0.0.1:8088
```

**不要直接绕过独立 Bootstrap。** 启动顺序为 Nacos healthy → 独立 `nacos-bootstrap` 完成 8 个 Mapping 分片 → Metadata → Scheduler×2 → Dispatcher×2 → Engine → API → Web。`start_dev_compose.sh` 有有界超时和日志检查，失败时退出非零。Compose Java 服务也声明 Bootstrap 的 `service_completed_successfully` 依赖。

## 当前服务和安全

`flow-api`、`flow-metadata`、`flow-scheduler`、`flow-dispatcher`、`flow-engine` 使用 OTRYX RPC **应用级 Nacos** 发现。MySQL 每服务独立 Schema，开发共享账号；生产需要每服务独立最小权限。后台 Session 目前是单节点；Worker SDK、受控 DAG 执行和公开 HTTPS 尚未实现。

仅静态站点 `flow-web` 绑定宿主机回环 `127.0.0.1:8088`。MySQL、Nacos（HTTP 8848、gRPC 9848）以及业务 RPC 仅存在于 `internal` Docker Network；不要暴露到公网。`NACOS_AUTH_ENABLE=false` 和 `TRUSTED_DEVELOPMENT` 是不安全的生产配置，当前 Adapter 还不支持完整 `NETWORK_MTLS`。

## 验证

```bash
bash scripts/verify_m1_offline.sh
# 真实 Compose E2E 的故障注入会重启 Metadata、Engine 等，仅在隔离测试环境执行：
set -a; source .env; set +a
FLOW_E2E_RESTART_METADATA=1 bash scripts/test_m1_e2e.sh
```

GitHub `.github/workflows/m1-quality.yml` 执行 Java、Vue、源码门禁；`.github/workflows/m1-integration.yml` 在 PR Ready 时通过真实 Nacos 服务执行全链路测试，并可手动运行。旧 Etcd CI 证据不是本次 Nacos 验收证据。

## 排错

1. Nacos 启动失败：检查 `docker compose logs nacos`；健康端点是 `/nacos/v3/admin/core/state/readiness`。
2. Bootstrap 失败：检查 `docker compose logs nacos-bootstrap`，核实 namespace/group/Config MD5。无法通过时停止启动；不要运行期修复元数据。
3. HTTP 502 / RPC Consumer registration timeout：用 `bash scripts/start_dev_compose.sh` 先启动 Provider；确认 Nacos 和 Config Mapping 已就绪。
4. API 401/CSRF 403：登录后带 Session Cookie，先 GET `/api/v1/auth/csrf`，随后使用 `X-XSRF-TOKEN`。
5. MySQL migration 失败：先备份数据并核对 Flyway 状态，不要直接删持久卷。

详细原理与升级/回滚请看 [Nacos 唯一注册中心](nacos-registry.md)。
