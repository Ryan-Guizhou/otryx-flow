# M2 FLOW-012：Dispatcher RPC / REST 契约

> Issue #7 实现候选。以 `flow-contracts/src/main/java/com/peachsoft/otryx/flow/contracts/v1/` 为最终源码权威，下面为审核辅助说明。

| 协议 | 方法 | 性质 | 备注 |
| --- | --- | --- | --- |
| `DispatcherRpc` | `enqueue(DispatchEnqueueCommand)` | RPC 幂等 | 固定 `runId`/eventId、Handler 参数与重试配额，返回 `CREATED` / `DUPLICATE` / `CONFLICT` |
| `DispatcherRpc` | `list(owner, project)` | RPC 只读 | 最多 100 条，禁止暴露 Worker leaseToken |
| `DispatcherRpc` | `claim(DispatchLeaseCommand)` | RPC 非幂等 | Worker 声明 ID/handlerKey，最多 8 条；各任务返回 `leaseEpoch` 与 `leaseToken` |
| `DispatcherRpc` | `heartbeat(DispatchHeartbeatCommand)` | RPC 非幂等 | 当前且未过期代际返回 `OK`，否则 `STALE` |
| `DispatcherRpc` | `complete(DispatchCompleteCommand)` | RPC 非幂等 | outcome 仅允许 `SUCCEEDED`/`FAILED`；成功 `OK`，旧代际 `STALE` |
| Flow API | `GET /api/v1/projects/{projectId}/dispatch-tasks` | REST Session 只读 | 项目所有权过滤，仅 Task 摘要，**不提供租约密钥** |

`DispatchEnqueueCommand` 所属请求来自受信任 Engine Relay，而非浏览器。它必须绑定 Engine 已提交的 Run 与 Metadata 的不可变 JobRevision；接收端校验 UUID、owner、Handler key、参数是最大 16KiB 的 JSON 对象、timeout 1..86400 秒、maxAttempts 1..10。权限与 Worker 认证在当前封闭开发环境外需要明确加固，不可直接暴露 RPC。

`DispatchTaskView.status`: `READY`（等待）、`LEASED`（持有）、`SUCCEEDED`（收到当前代际的成功回执）、`FAILED`（次数耗尽）。`EngineRunView.status=DISPATCHED` 只表示 Dispatcher 已确认持久入队；Engine 不会在此 PR 中自动标记逻辑 Run 成功。
