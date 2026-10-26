# M2 FLOW-014（Issue #9）真实端到端验收清单

最新 PR Head 必须通过以下 5 道门禁并留存原始 GitHub Actions 证据：

1. Java 21 Maven 全量 `clean verify`：Engine 的 Outcome Inbox 冲突/重投/状态不可倒退；Dispatcher Task+Attempt+Outcome Outbox 同事务；Relay ACK 丢失重投和 fencing 并发。
2. Vue/Node：状态枚举包含 `WAITING_DISPATCH`、`DISPATCHED`、`SUCCEEDED`、`FAILED`，用户按项目权限查看 Run 和 Task，Lease Token 不通过 REST 泄漏。
3. Source/Docs：Maven/Docker/CI、Nacos-only Bootstrap、Mermaid、双语 README 内容一致。
4. 真实 MySQL + Nacos 3.2.4 + 八分片 Bootstrap + 两 Scheduler + Engine + 两 Dispatcher + 独立 Java Worker：先跑历史所有 API/RPC、会话、Cron/DST、幂等、Outbox、重启和 Fencing 测试，不以 mock 替代。
5. 跨进程闭环：真实 `safe.echo` Worker 完成后 Engine Run = SUCCEEDED、Outcome Outbox DELIVERED、Inbox 唯一；注入 Engine Outcome ACK 丢失后二次重投仍为一条；真实 `safe.fail` Handler 按预算多次尝试后 Task/Run = FAILED；Worker 崩溃或旧 epoch 失效时无错误终态。

注意：测试中的 e2e Dispatcher 探针、故障注入、MySQL root 查询与强制重启**仅限隔离 CI Profile**。这些回归不会证明用户任意脚本执行安全、完整 DAG、跨地域灾备或公网生产可用性。
