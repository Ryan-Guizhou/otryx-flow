# FLOW-010 Scheduler API（实现候选）

> Spring Security Session + CSRF 保护写操作；通过 `flow-api` → OTRYX RPC 访问独立 Scheduler。所有路由内的 `projectId`、`jobId`、`scheduleId` 是 UUID，项目读取要求当前用户有权访问。

| Method | Path | Semantics |
| --- | --- | --- |
| GET | `/api/v1/projects/{projectId}/schedules` | 项目计划列表；有界最多 100 条 |
| POST | `/api/v1/projects/{projectId}/schedules` | Idempotency-Key 幂等创建，绑定指定已发布 JobRevision；201 新增、200 重放、409 冲突 |
| GET | `/api/v1/projects/{projectId}/schedules/{scheduleId}/triggers` | 最多 100 条已提交的逻辑 fire 记录，状态 `PENDING_ENGINE` |
| POST | `/api/v1/projects/{projectId}/schedules/{scheduleId}/enabled` | `expectedVersion` CAS 启停；失配返回 409 |

创建 JSON 示例：

```json
{"jobId":"11111111-1111-4111-8111-111111111111","jobRevision":1,"expression":"*/5 * * * * *","timeZone":"Etc/UTC","misfirePolicy":"FIRE_ONCE"}
```

启停 JSON 示例：

```json
{"expectedVersion":4,"enabled":false}
```

响应为 `ScheduleView`（含绑定的任务修订版本、UTC nextFire、version、enabled）或 `ScheduleTriggerView` 列表。尚未实现 Engine 和 Worker：响应中的 `PENDING_ENGINE` 仅说明数据库中存在逻辑火点。

RPC `ScheduleRpc`（接口版本 1.0.0）共享同一 `flow-contracts`；`create`、`list` 和 `triggers` 是幂等调用；`toggle` 是 CAS，不允许透明业务重试。缺失/未经授权资源统一映射 404，不透出内部数据库 ID。
