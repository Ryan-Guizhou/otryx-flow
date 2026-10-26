# 监控、日志、追踪与告警（Proposed）

## 关键标识
`requestId`（HTTP）、`traceId`（跨进程）、`projectId`、`scheduleId`、`triggerKey`、`workflowRunId`、`nodeRunId`、`dispatchKey`、`attemptId`、`leaseEpoch`。服务日志禁止承载敏感参数、证书指纹明文列表或 Secret。Trace 关联不是 RPC 授权凭据。

## 指标类别
| 类别 | 代表指标（拟议命名） | 用途 |
|---|---|---|
| Scheduler | `flow_schedule_due_lag_seconds`、`flow_schedule_trigger_total` | 调度延迟、漏触发 |
| Engine | `flow_run_active`、`flow_node_transition_total` | DAG 收敛和活跃实例 |
| Dispatcher | `flow_dispatch_backlog`、`flow_dispatch_lease_expired_total` | 排队与租约故障 |
| Worker | `flow_worker_available_slots`、`flow_task_execution_seconds` | 资源容量与执行时延 |
| Outbox | `flow_outbox_pending`、`flow_outbox_oldest_seconds` | 跨服务投递可靠性 |
| Security | `flow_auth_denied_total`、`flow_certificate_expiry_seconds` | 越权与证书风险 |
| RPC | 使用 OTRYX Micrometer 与 OTel 提供的实际指标 | RPC 请求、超时、熔断与负载 |

以上 `flow_*` 是新产品拟议的业务指标，M1/M2 后才会实现；不要声称已存在。Prometheus 标签不能包含无限个 runId/attemptId，应放 Trace/log，而不是高基数 Metrics label。

## 告警
触发积压持续上升、Outbox 最旧未交付、租约连续失效、Worker 可用容量归零、DB 不可用、Etcd 安全连接故障、证书临近到期、失控重试与运行成功率异常。告警记录系统级恢复状态和负责人，Demo 环境频率需限制。

## 诊断与值班
前端每条 Run 展示其业务 ID、Trace 关联链接与最后错误分类；运维手册要求按 `Run -> Node -> Attempt -> RPC trace -> Worker logs` 找出问题，所有修复操作必须审计。正式仪表盘在 M4 由代码生成并配套截图/测试证据。
