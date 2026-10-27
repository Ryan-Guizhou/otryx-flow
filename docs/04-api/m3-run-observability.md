# FLOW-019：DAG Run 观测 API 契约

所有浏览器接口均要求有效 Session；所有非 GET 请求继续要求 CSRF 与项目级 ADMIN。服务端权限校验为权威边界。

## 读取 Run 与控制历史

- `GET /api/v1/projects/{projectId}/workflow-runs`：读取当前项目的 DAG Run 列表。
- `GET /api/v1/projects/{projectId}/workflow-runs/{runId}`：读取 Run 与 NodeRun 详情。
- `GET /api/v1/projects/{projectId}/workflow-runs/{runId}/history`：读取取消/重试审计事件。
- `POST .../{runId}/cancel`：取消意图；提交当前 `expectedRunVersion` 与 `Idempotency-Key`。
- `POST .../{runId}/nodes/{nodeId}/retry`：重试失败节点；每次逻辑重试生成新 NodeRun ID。

取消/重试的状态机、版本竞争和幂等规则继续遵守 [FLOW-017 契约](m3-dag-control-contracts.md)。

## 查询日志

`GET /api/v1/projects/{projectId}/workflow-runs/{runId}/logs?limit=100&cursor={opaqueCursor}`

| 字段 | 说明 |
|---|---|
| `items` | 当前页日志数组，按 createdAt、id 倒序 |
| `nextCursor` | 有下一页时返回的不透明游标，否则为 null |
| `limit` | 实际请求页大小，范围 1–100 |

每条日志仅返回 `id`、`nodeRunId`、`taskId`、`leaseEpoch`、`sequence`、`level`、`message`、脱敏后的 `fieldsJson` 与 `createdAt`。不返回 lease token、claim token、payload fingerprint 或内部凭据。

API 先通过 Engine 验证 Run 与项目/用户归属，再将其 NodeRun ID 集合传给 Dispatcher；单次最多 300 个 NodeRun ID。无节点的 Run 返回空页。limit 越界、游标过长或格式错误返回 400；跨项目或不存在的 Run 返回 404；未登录返回 401。

## Worker 内部 RPC

- `DispatcherRpc.appendLogs(DispatchLogBatchCommand)`：内部 Worker 日志批量写入。请求含 workerId、taskId、leaseEpoch、leaseToken 与 1–32 条有序事件。leaseToken 只在内部 RPC 内传递，不落日志表，也不出现在浏览器响应。
- `DispatcherRpc.queryLogs(ownerUsername, projectId, nodeRunIds, cursor, limit)`：仅供 Flow API 内部使用；owner/project 条件和 NodeRun ID 必须同时匹配。

事件 sequence 从每个 lease attempt 的 1 开始。重复 sequence 返回 duplicate 计数而不重复插入；旧 lease epoch 返回 STALE；单次执行达到 1000 条日志预算后返回 LIMIT_REACHED 并丢弃超额条目。服务端对消息和字段再次校验、截断和脱敏。Worker 日志失败不得影响 heartbeat、complete 或 Engine 状态推进。

## 错误与安全

- `401`：缺少或过期 Session。
- `403`：CSRF 或角色权限不满足。
- `404`：Run 不存在或不属于该项目。
- `409`：Run 版本 CAS/幂等键冲突（取消/重试）。
- `400`：无效页大小、游标或请求结构。
- `502/503`：下游 RPC 不可用；客户端保留页面状态，不自动重放有副作用的控制请求。

完整场景见 [FLOW-019 验收计划](../06-testing/m3-run-observability-acceptance.md)。
