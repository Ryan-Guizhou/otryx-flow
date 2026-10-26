# ADR-001：Worker Pull + 持久租约

**状态：Proposed / 2026-10-23**

**背景**：OTRYX RPC 当前基线以 Unary RPC 为主；直接在 RPC 上持续执行几十分钟的任务会混淆 Deadline、断链和业务完成语义。

**决策**：Worker 主动领取 DispatchTask；Dispatcher 事务创建 `attemptId+leaseEpoch` 以及到期时间。心跳是短时 RPC；完整业务 Handler 在 Worker 内独立运行。Lease 失效生成下一 Attempt，旧版本 Completion 必须被拒绝。

**结果**：可在网络波动下恢复，但存在重复物理尝试；业务侧负责外部副作用幂等。由于数据库竞争，计划设置有界批量领取、轮询抖动、队列深度告警；规模化后评估独立 Broker Adapter。

**替代**：同步推送拒绝；全消息队列中心式方案延后；Redis 锁不作为数据库任务真相源。
