# FLOW-011 Engine RPC 与 REST 契约

## RPC（仅内部可信服务）

`EngineRpc.acceptScheduledFire(ScheduleFireCommand)`，使用 OTRYX RPC `version=1.0.0`。请求字段：`eventId`, `ownerUsername`, `projectId`, `scheduleId`, `scheduleRevision`, `jobId`, `jobRevision`, `plannedFireUtc`, `origin=CRON`。所有 UUID 必须是标准格式；UTC 使用 ISO 8601 字符串。负载字段必须和 Scheduler 持久化 Trigger 的业务快照一致。

返回 `EngineFireReply(code,runId,runStatus)`：

- `CREATED`：新建 Inbox 和 Run，`runStatus=WAITING_DISPATCH`。
- `DUPLICATE`：相同 `eventId` 与内容已生效，返回**原 runId** 与已有状态。
- `CONFLICT`：事件 ID 与负载冲突，或同一个逻辑火点被其它事件抢占；Scheduler 进入 `DEAD`，不 ACK。

`EngineRpc.listRuns(ownerUsername,projectId)` 最多返回 100 个最近 Run（`EngineRunListReply`），在 Engine DB 依据 owner/project 过滤。RPC 参数代表已由可信 API/服务验证的用户作用域；生产部署必须启用服务身份认证与 mTLS。

## REST（需登录）

`GET /api/v1/projects/{projectId}/runs`：HTTP 200，数组元素结构：

```json
[
  {
    "id": "00000000-0000-4000-8000-000000000004",
    "eventId": "00000000-0000-4000-8000-000000000005",
    "projectId": "00000000-0000-4000-8000-000000000001",
    "scheduleId": "00000000-0000-4000-8000-000000000002",
    "scheduleRevision": 1,
    "jobId": "00000000-0000-4000-8000-000000000003",
    "jobRevision": 2,
    "plannedFireUtc": "2026-10-26T08:00:00Z",
    "origin": "CRON",
    "status": "WAITING_DISPATCH",
    "createdAt": "2026-10-26T08:00:01Z"
  }
]
```

- 401：未登录；403：权限拒绝；400：Project ID 不合法。
- 下游 OTRYX RPC 失败由统一错误处理返回 503 / 502，不透出内部异常。
- 此 API 是只读，不会触发 Worker，也不接受请求方直接提供 `eventId` 创建 Run。

## 约定与非目标

`flow_schedule_trigger` 的 `ENGINE_ACCEPTED` 和 Engine Run 的 `WAITING_DISPATCH` 是控制面中间状态；**均不能解释为任务成功或已开始执行**。暂不开放手动回放、Inbox 删除、批量无界查询或公共匿名触发接口。
