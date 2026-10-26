# Cron 预览 REST API（FLOW-009）

> 当前阶段仅提供受权限保护的同步、无副作用计算，不创建 Schedule 或 JobRun。

- `POST /api/v1/schedules/preview`：需 ADMIN 登录及既有 CSRF，会话 Cookie，`Content-Type: application/json`。

```json
{
  "expression": "0 30 2 * * *",
  "timeZone": "Europe/Berlin",
  "fromUtc": "2026-03-28T00:00:00Z",
  "limit": 3
}
```

成功返回 200：

```json
{
  "expression": "0 30 2 * * *",
  "timeZone": "Europe/Berlin",
  "fromUtc": "2026-03-28T00:00:00Z",
  "occurrences": [
    { "utc": "2026-03-28T01:30:00Z", "local": "2026-03-28T02:30", "offset": "+01:00" },
    { "utc": "2026-03-30T00:30:00Z", "local": "2026-03-30T02:30", "offset": "+02:00" }
  ],
  "overlapOccurrencesSkipped": 0
}
```

`occurrences` 是有序列表，可能包含更多项目（例如 `limit=3` 会继续返回下一天），上例截取前两项用于说明 DST gap。`local` 是不带偏移的 ISO 本地时间；必须以 `utc` 作为唯一执行时刻。`timeZone` 是 IANA 标识。

- 非法 Cron（非六字段、无法解析）、时区非法、`limit` 越界、`fromUtc` 无法解析：400。表达式解析错误为 `INVALID_CRON_SCHEDULE`；Bean Validation 400 使用框架默认响应。
- 未登录 401、无 ADMIN 权限 403、缺少 CSRF 403。

完整语义参见 [时区设计](../03-design/cron-timezone.md)。
