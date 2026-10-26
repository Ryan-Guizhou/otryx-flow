# FLOW-011 / Issue #6 Engine 与 Scheduler Outbox 验收

> 这是工程测试契约，不是预填的测试通过报告。以 PR Head 的 GitHub Actions 原始结果为事实证据。

## 逐项验收

| Case | 前置 / 动作 | 断言 |
|---|---|---|
| ENGINE-01 | 对已发布 JobRevision 建立真实 Cron Schedule | Scheduler Outbox `eventId` 固定，Engine 生成 `WAITING_DISPATCH` Run |
| ENGINE-02 | 让两个 Scheduler 副本同时扫描/Relay | Trigger/Outbox/Engine Run/InBox 一一对应，不重复逻辑火点 |
| ENGINE-03 | 同一事件以原负载重复发 RPC | `DUPLICATE` 返回原 `runId`，Run 数不变 |
| ENGINE-04 | 同一 `eventId` 改动 JobRevision 或计划 | `CONFLICT`，Run 不被覆盖 |
| ENGINE-05 | 模拟已提交后 ACK 丢失，重投相同 Outbox | 再次变成 `DELIVERED`，原 Run 不变 |
| ENGINE-06 | Engine DB 事务第二步故障 | Inbox 与 Run 都不会产生半提交 |
| ENGINE-07 | 主动重启 Engine 后继续投递历史 Outbox | 自动恢复，原 logical fire 仍唯一 |
| ENGINE-08 | 模拟领取 token 被新实例替换 | 旧 ACK 不更改 Outbox/Trigger 权威状态 |
| ENGINE-09 | 未登录 / 其它 owner 查询 Run | 401 / 返回空数组，不能跨项目读取 |
| ENGINE-10 | 全部 M1、M2 Job/Cron/Scheduler 回归 | Maven/JUnit、Vue、Docs、真实 Compose 全部通过 |

`bash scripts/test_m1_e2e.sh` 在 GitHub Compose 工作流中调用 `test_m2_job_e2e.sh` → `test_m2_scheduler_e2e.sh` → `test_m2_engine_e2e.sh`。测试必须启动 MySQL、Etcd、独立 Metadata/Api/Scheduler×2/Engine JVM，不能用模拟 HTTP 替代真实 RPC 证据。

## 故障注入与安全界限

- CI 用临时随机数据库和管理员密码，执行后 `docker compose down --volumes`；绝不指向公网生产。
- 单测覆盖 H2 方言下同一逻辑 fire 的唯一性。真实 MySQL 集成覆盖 DB session UTC、Flyway、服务发现、Outbox 状态迁移及重启。
- 若 Engine 未就绪，Scheduler 不应把 Outbox 标为 `DELIVERED`；错过 ACK 后可重试同一事件。
- 公网访问需要切换 `NETWORK_MTLS`、TLS、用户身份、配额与审计，本阶段开发 Compose 不可直接公开。
