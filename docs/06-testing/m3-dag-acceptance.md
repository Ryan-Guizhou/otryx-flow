# FLOW-016 / Issue #21 / PR #22 验收标准

**未完成 CI 前不可宣称完整 DAG 已实现。**

| 门禁 | 通过条件 |
|---|---|
| Backend | Java 21 Maven clean verify，包括依赖推进、CAS、Outbox/Inbox、Relay、REST 单测 |
| Frontend | Node HTTP 合约与 Vue TypeScript 构建通过 |
| Source、Docs | 静态检查、双语标记、链接完整 |
| 真实 Nacos + MySQL + RPC | 独立 Metadata、Engine、Dispatcher、至少两个 Worker 容器 |
| Diamond | A→(B∥C)→D；D 必须等待 B/C 持久终态，只生成一条 Dispatcher Task |
| 条件分支 | TRUE/FALSE 两种输入正确传播 SKIPPED，D 完成 |
| 故障注入 | Engine 带持久 READY Outbox 重启后恢复，Dispatcher 终态 ACK 丢失重投不重复 Inbox |
| 失败重试 | safe.fail 在 Dispatcher 两次合法 Worker Attempt 后收敛父 Run FAILED |
| 版本/权限 | R1 Run 与 R2 发布隔离；HTTP 幂等，匿名 401、CSRF 403、跨项目 404 |
| 合并 | 最新 PR Head 精确对应全部 5 项检查成功，Squash Merge main 并关闭 Issue #21 |

相关设计[运行时](../03-design/m3-dag-runtime.md)与[API/RPC](../04-api/m3-dag-runtime-contracts.md)。取消/人工重试、可视化画布及生产 mTLS 属于后续工作。

