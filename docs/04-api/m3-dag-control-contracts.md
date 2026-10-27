# M3 FLOW-017：取消与重试 REST / RPC

**状态：待 PR #24 最新 Head 全部 CI 通过。** 接口在已有 `/api/v1/projects/{projectId}/workflow-runs` 下扩展。

| 方法 | 路径 | 权限 | 返回 |
|---|---|---|---|
| POST | `/{runId}/cancel` | ADMIN、Session、CSRF、Idempotency-Key | DagControlReply |
| POST | `/{runId}/nodes/{nodeId}/retry` | ADMIN、Session、CSRF、Idempotency-Key | DagControlReply |
| GET | `/{runId}/history` | Session、当前项目归属 | 最多 100 个 DagControlAuditView |
| GET | `/{runId}` | Session、当前项目归属 | DagRunView.runVersion、所有 NodeRun.logicalAttempt 历史 |

POST Body：`{"expectedRunVersion":2}`；请求键为 16–80 个安全字符。请求版本必须来自最新 GET/POST 响应。不允许客户端提交 ownerUsername；服务端从 Session Principal 获取，并经过 Engine DAO 项目+owner 双重过滤。

`DagRpc.cancel(DagCancelCommand)`、`DagRpc.retry(DagRetryCommand)`、`DagRpc.history(owner,project,runId)` 均通过 OTRYX RPC v1。取消和重试的相同请求键在数据库唯一约束下保证幂等；网络不确定时不得替换请求键。

`DispatcherRpc.cancelDagTask(DispatchCancelCommand)` 以 NodeRun ID 为稳定键，返回 `CANCELLED`（租约已 fenced，包括从未 Enqueue 的 Tombstone）、`TERMINAL`（已持久成功或失败，Engine 等候原 Outcome）、`CONFLICT`（身份不一致）。该接口不能用于客户端直接取消其他项目任务。

错误映射：成功/重复 200；参数无效 400；不存在或非当前项目 404；旧版本、已完成 Run、退避预算不满足、同键修改或不允许重试 409；未登录 401、CSRF/角色拒绝 403。不返回 leaseToken、内部取消 Outbox claimToken 或控制请求指纹。

NodeRun 字段 `logicalAttempt`（从 1 递增），父 Run 增加 `runVersion` 与 `CANCEL_REQUESTED/CANCELLED` 状态。客户端在 CANCEL_REQUESTED 时显示**取消处理中**而非取消成功。详细安全与竞态定义参见[设计](../03-design/m3-dag-control.md)。

