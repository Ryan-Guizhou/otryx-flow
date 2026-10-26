# 错误码设计（Proposed）

业务错误 Envelope：`code`（稳定且枚举）/ `message`（可本地化）/ `requestId` / `details`（仅允许字段）。具体错误码尚未实现，M1 以后应提供枚举和中英消息映射。

| 业务错误码 | HTTP 建议 | 业务处理 |
|---|---|---|
| `FLOW-VALIDATION-001` | 422 | DAG 存在环，前端聚焦相关边 |
| `FLOW-VALIDATION-002` | 422 | Cron/时区非法 |
| `FLOW-AUTH-001` | 403 | 无项目权限，审计 |
| `FLOW-REVISION-001` | 409 | Draft 版本冲突，客户端可比较后重试 |
| `FLOW-SCHEDULE-001` | 409 | 调度并发策略拒绝或跳过 |
| `FLOW-DISPATCH-001` | 429 | Worker 资源额度不足，遵守退避 |
| `FLOW-LEASE-001` | 409 | Attempt 或 Epoch 已过期，拒绝覆盖 |
| `FLOW-RUN-001` | 409 | 终态 Run 不能非法变更 |
| `FLOW-DEPENDENCY-001` | 503 | 内部服务不可用，持久命令在 Outbox 等待 |
| `FLOW-SECURITY-001` | 403 | 可信身份校验或方法授权被拒绝 |
| `FLOW-RECOVERY-001` | 409 | 副作用结果不确定，人工复核 |

日志中可记录错误分类但不要记录证书、口令、运行参数中的密钥；内部 RPC 框架异常和用户可见 REST 错误码要做显式映射。
