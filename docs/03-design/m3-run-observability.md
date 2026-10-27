# M3 FLOW-019：DAG Run 观测、Attempt 历史与日志

**状态：实现候选；验收以 Issue #27 对应 PR 最新 Head 的门禁和真实 Compose 证据为准。** 本阶段扩展现有执行控制能力，不修改 FLOW-016 的 DAG 状态机或 FLOW-017 的取消/重试 fencing 规则。

## 数据所有权与展示边界

- Engine 是 DAG Run、NodeRun 状态、条件分支结果、logicalAttempt 与控制审计的权威来源。
- Dispatcher 是物理 Worker lease/attempt 和执行日志的权威来源。日志不决定任务成功或失败。
- Web 只通过带 Session/CSRF 的 Flow API 访问数据。API 必须先由 Engine 验证 Run 属于当前用户和项目，再按该 Run 的 NodeRun ID 查询 Dispatcher 日志。
- M2 Scheduler/Job Run 与 M3 DAG Run 是两种不同状态模型。界面分别呈现，不用 M2 的 `WAITING_DISPATCH`、`DISPATCHED` 状态模拟 DAG 状态。
- 浏览器不得接触 leaseToken、claimToken、payload fingerprint、数据库连接信息或 RPC 内部凭据。

## Run / NodeRun 观测

Run 列表展示 workflowId、固定 workflowRevision、graph SHA-256、Run 状态、创建/更新时间和耗时。详情展示 Run 版本、每个 NodeRun 的节点 ID、类型、logicalAttempt、状态、条件决策和更新时间；同一节点的旧 logicalAttempt 保留为历史，不覆盖旧记录。

`RUNNING`、`CANCEL_REQUESTED`、`CANCELLED`、`SUCCEEDED`、`FAILED` 必须区分。取消按钮只提交取消意图，直到 Engine/Dispatcher fencing 收敛后才显示终态。人工重试必须产生新的 NodeRun ID 与 logicalAttempt；Dispatcher 内部物理尝试次数不能冒充 logicalAttempt。

取消和重试请求使用当前 `runVersion` 与稳定 `Idempotency-Key`。网络结果不确定时复用同一 key；409 表示版本竞争，刷新权威状态后由用户决定是否重试，不自动重复控制操作。

## 结构化日志链路

`WorkerContext.log` → Worker Runtime 有界队列 → 内部 OTRYX RPC `DispatcherRpc.appendLogs` → Dispatcher MySQL `flow_dispatch_log` → Flow API owner/project-scoped 查询 → Web 游标分页。

- Worker 本地队列容量 256；每批最多 32 条；每条消息最多 2 KiB UTF-8；每条最多 16 个字段；字段 JSON 最多 4 KiB。
- 每个结构化字段值最多持久化 80 字节，字段 JSON 总计不超过 4 KiB；每个物理 lease attempt 最多持久化 1000 条日志。超预算或本地队列满时日志会被计数丢弃，不能阻塞 handler、心跳或完成 ACK。
- `(task_id, lease_epoch, sequence_no)` 唯一约束保证批次重放幂等；过期 lease generation 不得追加日志。当前 generation 的终态 ACK 后允许短暂补刷已缓冲日志。
- 服务端对消息中的 password/token/secret/authorization/cookie/credential/API key 赋值及 Bearer token 做脱敏；同名结构化字段值统一替换为 `[REDACTED]`。字段值中的敏感赋值也执行脱敏。
- 查询按创建时间与日志 ID 倒序游标分页，每页 1–100 条，单次最多关联 300 个 NodeRun ID（100 个节点及有限 logicalAttempt）。只允许同一 owner/project 下的 NodeRun。
- 日志保留 14 天，定时任务每次最多删除 10,000 条；状态表、Outbox/Inbox 和 Attempt 记录不随日志保留任务删除。

## 故障与安全语义

日志属于尽力而为的诊断数据，不属于业务状态事件。日志上报失败不会阻止任务执行或完成；客户端收到不确定错误时用相同序号重试，服务端唯一约束避免重复。若 Worker lease 已过期且新 generation 已领取任务，旧 generation 日志被拒绝。

前端错误文案不得渲染内部异常堆栈。跨项目 Run ID 查询应由 API 返回 404，不泄露资源存在性；匿名、CSRF 失败和角色限制仍由统一安全配置执行。

相关接口与验收见 [FLOW-019 API 契约](../04-api/m3-run-observability.md) 和 [验收计划](../06-testing/m3-run-observability-acceptance.md)。
