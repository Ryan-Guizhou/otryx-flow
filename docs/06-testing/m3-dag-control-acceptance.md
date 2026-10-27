# FLOW-017 / Issue #23 / PR #24：取消与重试验收

**状态：未通过真实 Compose 及五项 CI 前不得合并。**

| 门禁 | 完成定义 |
|---|---|
| Backend | JDK21 `mvn -B -ntp clean verify`，Engine/Dispatcher H2 测试含取消预栅栏、租约 fencing、CAS/ACK 重放、人工逻辑重试 |
| Frontend | Node API 协议测试和 Vue 构建，通过 Session/CSRF/项目限定的取消与重试调用 |
| Source + Docs | `scripts/check_m1_source.py` 与 `scripts/check_docs.py` 全绿 |
| Compose | 实际 Nacos 3.2.4/MySQL/OTRYX RPC/Dispatcher/Worker，回归 FLOW-015/016 及 M2 |
| 未派发取消 | DAG 在 Worker 未运行时取消，Worker Attempts = 0；Dispatcher 确认 CANCELLED 占位或已存在任务；父 Run 最终 CANCELLED |
| 在途取消 | CI-only Probe 真实获取 lease，取消后旧 heartbeat / complete 都 STALE；无 SUCCEEDED 被伪造 |
| 终态竞态 | 先完成后取消保持 SUCCEEDED；先取消后收到已持久 Dispatcher 终态保留节点真实 SUCCEEDED；不存在覆盖 |
| 丢失 ACK/重启 | 强制重投同一 Cancel Outbox，Engine 重启后再次确认；Dispatcher 任务唯一，父 Run 最终状态正确 |
| 逻辑 Retry | FAILED Run 重试后生成不同 NodeRun ID、logicalAttempt 2，旧失败记录不改；两次 Worker Attempts 与逻辑重试严格区分 |
| 安全/幂等 | 重放相同键返回 DUPLICATE、同键不同版本 409、匿名 401、CSRF 403、跨项目 404 |
| Gate | PR 最新 Head 5/5 绿色证据，Squash Merge main，Issue #23 关闭 |

运行脚本：`scripts/test_m3_control_e2e.sh` 通过 `scripts/test_m1_e2e.sh` 的 FLOW_E2E_M3_CONTROL 开关调用，在已有 M3 DAG E2E 后运行。非生产 e2e profile 不得提供公网端口。

已知边界：取消只保证控制面停止新派发及已知租约 fencing，不能撤销已发生的外部业务副作用。完整生产 mTLS、回滚/补偿工作流、脚本执行、可编辑 Vue Flow 画布均不在本期范围。参见[设计](../03-design/m3-dag-control.md)和[RPC 合约](../04-api/m3-dag-control-contracts.md)。

