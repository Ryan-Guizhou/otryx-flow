# OTRYX Flow 本地开发 Compose 架构（唯一 Nacos 3.2.4）

> 本地开发与隔离 CI 使用。此配置不具备生产可用的 Nacos mTLS/公网 HTTPS 安全能力。

## 前置条件

- Docker Engine + Compose V2、Bash、OpenSSL，以及可访问 GitHub、Maven 和 npm 源的网络。
- Java 21 与 Node 22 构建环境在 Docker 镜像内准备；宿主机无需单独安装 JDK 或 Node.js。
- 当前固定 OTRYX RPC 源码版本为 `0addd7df17c5ba4a0b77e2d30e4c7c75d7ac31f2`，尚未发布到 Maven Central。

## 一键启动

```bash
./scripts/dev.sh up
```

脚本在本机缺少 `.env` 时生成唯一随机凭据（权限 `0600`），构建镜像并调用 `start_dev_compose.sh`。**不要直接绕过独立 Bootstrap。** 启动顺序为 Nacos healthy → 独立 `nacos-bootstrap` 完成 8 个 Mapping 分片 → Metadata → Scheduler×2 → Dispatcher×2 → Engine → API → Web → 示例 Worker。启动脚本有界等待 RPC 应用启动日志；失败时返回非零并展示有限日志。

## 当前服务和安全

`flow-api`、`flow-metadata`、`flow-scheduler`、`flow-dispatcher`、`flow-engine` 使用 OTRYX RPC **应用级 Nacos** 发现。MySQL 每服务独立 Schema，开发共享账号；生产需要每服务独立最小权限。当前可用核心包括任务/工作流版本管理、Cron 与手动触发、DAG 执行、Worker 租约与 fencing、取消/重试、运行与 Attempt 历史及有界脱敏日志。后台 Session 目前是单节点；生产安全、集群级配额与备份恢复仍需后续完善。

仅静态站点 `flow-web` 绑定宿主机回环 `127.0.0.1:8088`。MySQL、Nacos（HTTP 8848、gRPC 9848）以及业务 RPC 仅存在于 `internal` Docker Network；不要暴露到公网。`NACOS_AUTH_ENABLE=false` 和 `TRUSTED_DEVELOPMENT` 是不安全的生产配置，当前 Adapter 还不支持完整 `NETWORK_MTLS`。

## 验证

```bash
bash scripts/verify_m1_offline.sh
# 真实 Compose E2E 的故障注入会重启 Metadata、Engine 等，仅在隔离测试环境执行：
set -a; source .env; set +a
FLOW_E2E_RESTART_METADATA=1 bash scripts/test_m1_e2e.sh
```

GitHub `m1-quality` 在 PR 上执行 Java、Vue 和源码门禁。完整真实 Compose 故障注入由手动触发的 `m1-integration` 工作流执行，避免每次推送都重复构建多个 JVM 镜像；观测组件也由独立 Compose overlay 按需启动。

## 排错

1. Nacos 启动失败：检查 `docker compose logs nacos`；健康端点是 `/nacos/v3/admin/core/state/readiness`。
2. Bootstrap 失败：检查 `docker compose logs nacos-bootstrap`，核实 namespace/group/Config MD5。无法通过时停止启动；不要运行期修复元数据。
3. HTTP 502 / RPC Consumer registration timeout：用 `bash scripts/start_dev_compose.sh` 先启动 Provider；确认 Nacos 和 Config Mapping 已就绪。
4. API 401/CSRF 403：登录后带 Session Cookie，先 GET `/api/v1/auth/csrf`，随后使用 `X-XSRF-TOKEN`。
5. MySQL migration 失败：先备份数据并核对 Flyway 状态，不要直接删持久卷。

详细原理与升级/回滚请看 [Nacos 唯一注册中心](nacos-registry.md)。
