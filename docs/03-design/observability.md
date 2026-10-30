# OTRYX Flow 可观测性与诊断

## 当前状态

FLOW-021 / [Issue #30](https://github.com/Ryan-Guizhou/otryx-flow/issues/30) 仍在推进。PR #31 交付第一阶段：接入 OTRYX RPC Micrometer/OpenTelemetry Adapter、内部 Actuator 端口、本地 Prometheus/Grafana/OTLP Collector、Scheduler/Engine/Dispatcher 持久化指标快照、Worker 运行时指标，以及严格的 RPC Trace 证据校验器。完整跨 JVM 验收以 PR 最新 Head 的 Compose 门禁结果为准；不能仅凭依赖或配置宣称传播正确。

OTRYX RPC 固定基线：0addd7df17c5ba4a0b77e2d30e4c7c75d7ac31f2（已合并的 OpenTelemetry 自动配置顺序修复）。注册中心继续使用 Nacos 3.2.4；生产 mTLS/Nacos TLS 由 [FLOW-020 / Issue #29](https://github.com/Ryan-Guizhou/otryx-flow/issues/29) 与上游 [OTRYX RPC #179](https://github.com/Ryan-Guizhou/otryx-rpc/issues/179) 处理。

## 本地启动

在根目录复制并编辑 .env，设置所有数据库/管理员凭据以及强随机的 FLOW_GRAFANA_ADMIN_PASSWORD。仅在隔离开发环境执行：

~~~bash
docker compose -f compose.yaml -f compose.observability.yaml config --quiet
docker compose -f compose.yaml -f compose.observability.yaml up --build -d
~~~

Grafana 仅绑定宿主机回环地址：http://127.0.0.1:3000。Prometheus 和 OTLP Collector 不发布宿主机端口，仅供 Docker internal 网络使用。停止：

~~~bash
docker compose -f compose.yaml -f compose.observability.yaml down
~~~

## 数据流

- Flow API、Metadata、Scheduler、Engine、Dispatcher 的 Actuator 使用容器内管理端口 9091，暴露 health、info、prometheus；管理端口不映射到宿主机。
- Prometheus 抓取 Flow 服务的 Actuator 与 OTRYX RPC Micrometer 指标。
- OpenTelemetry 通过 OTLP/HTTP 将 Trace 发送给 Collector；Collector 的 debug exporter 使用 detailed 模式仅用于隔离开发/CI 的 Span 级关联验收，不是生产 Trace 后端；部署到长期运行的开发环境时应评估日志量。
- Worker 进程保持 non-web 形态，默认关闭 OTLP 指标出口；在本地 Overlay 中显式开启后，通过 OTLP 推送可用槽位、活动任务、成功/失败/取消计数、累计执行时长与日志丢弃计数，Collector 再将指标暴露给内部 Prometheus。
- Grafana 从 Prometheus 读取指标。当前 Dashboard 和规则是起始模板，不代表生产 SLO 或容量承诺。

## 基数与敏感信息

禁止将 projectId、runId、nodeRunId、attemptId、traceId、用户输入或异常消息作为 Meter 标签。业务 ID 只可用于经过脱敏的日志或 Trace 属性。不得导出 Cookie、Authorization、Token、密码、证书私钥或业务参数正文。管理端口不得映射到宿主机或公开反向代理。

## 诊断顺序

1. 检查 Prometheus targets 是否为 UP；若 DOWN，查看对应服务健康状态与 Compose 日志。
2. 检查 Grafana 的 RPC logical call、network attempt、retry、错误与延迟指标。
3. 通过 Collector 日志检查 Trace 是否到达，并验证真实跨 JVM RPC 是否保留 W3C Trace Context。
4. 从 DAG Run 页面查看 Run → Node → Attempt，再关联 Worker 日志；日志不作为任务终态权威来源。
5. 遥测导出失败不得改变 RPC 返回值、Worker 心跳、完成 ACK 或业务状态。

## 后续待完成验收

- [x] 为 Scheduler、Engine、Dispatcher、Worker 增加权威数据库快照或有界运行时指标。
- [x] 在真实 Compose 中验证 Prometheus 抓取、关键业务指标查询、规则加载与 Collector 收集 Trace。
- [x] 为 OTRYX RPC Trace 增加单元测试，并在真实验收中检查跨服务 client/server Span 的 Trace ID、Parent Span ID 与 method ID 关系。
- [x] 真实 RPC 成功调用的 logical-call/network-attempt 计数及低基数标签校验（PR #35 CI）。
- [ ] 真实故障路径的 retry/timeout 计数语义仍需专项可重复触发证据。
- [x] HTTP/API 401、403 安全响应计数与敏感字段 Meter 标签边界。
- [x] 数据库指标刷新错误状态与恢复单测；Collector 断连时 Worker Heartbeat、complete、Dispatcher/Engine 终态的真实 Compose 故障注入。
- [x] Prometheus 数据库刷新故障与 Collector 不可用告警表达式及 promtool 触发/恢复测试。
- [ ] Collector 在线但下游 exporter 失败的直接可观测信号及故障测试，尚不宣称验证所有出口故障。
- [ ] 生产 mTLS/Nacos TLS 与公开 HTTPS 仍由独立安全工作项负责。

## FLOW-021 后续验证：HTTP 安全、数据库刷新与遥测隔离

PR #35（Issue #34）新增低基数 HTTP 响应指标 `flow_http_responses_total`。只使用
`status_class`（1xx/2xx/3xx/4xx/5xx/unknown）与
`security_event`（none/authentication_failure/authorization_denied）两类固定标签。
403 也可能来自 CSRF 校验，因此此安全事件计数是拒绝类别，不可直接推断具体用户越权。

Scheduler、Engine、Dispatcher 的 `flow_database_metrics_refresh_state` Gauge 不包含业务 ID 标签。
0 表示尚未完成首次采样，1 表示最近一次数据库指标刷新成功，2 表示最近一次刷新失败。
故障时旧业务 Gauge 保留上次成功的快照；必须结合刷新状态使用，不能把旧值当成实时状态。
数据库恢复后成功刷新会将状态从 2 改为 1。告警阈值是环境模板，不代表正式生产 SLO。

`FlowTelemetryCollectorUnavailable` 使用内部 Prometheus 的 `up{job="otel-collector"}`
确认 Collector 抓取端点不可用。**这不是所有 SDK OTLP 数据导出失败的直接计数**；
Collector 在线而实际 exporter 失败的完整证据仍需专门注入，不能根据此告警宣称已验证。

固定 OTRYX RPC Micrometer Observer 的真实指标契约如下：

- `otryx_rpc_client_calls_seconds_count`：logical call 的 Micrometer Timer 次数。
- `otryx_rpc_client_attempts_seconds_count`：网络 attempt 的 Micrometer Timer 次数。
- `otryx_rpc_client_retries_total`：已安排的 retry 事件次数。
- `otryx_rpc_client_timeouts_total`：逻辑调用 DEADLINE_EXCEEDED 次数。

计数不能混用：调用在发起网络 attempt 前失败时，logical call 与 attempt 可不相同。
`scripts/test_rpc_metric_semantics.sh` 在真实 Nacos 与 MySQL 环境中执行 REST→OTRYX RPC 调用，
比较调用前后的 Actuator 样本，并检查 RPC Meter 不含项目、Run、Trace、用户名等高基数标签。
正常成功流量可能没有 retry/timeout；因此零计数**不等于**完整验证了它们的失败语义。

`scripts/test_telemetry_failure_isolation.sh` 仅在隔离 Compose 中运行：
关闭 Collector 后，创建固定 12 秒的可信 `safe.delay` 任务，验证真实 Worker 心跳延长
Dispatcher 持久化租约、Dispatcher 与 Engine 最终成功、Outcome Outbox 为 DELIVERED，
同时从 Prometheus 证实 Collector 不可用，结束后恢复 Collector。
`safe.delay` 仅在 `FLOW_E2E_DELAY_HANDLER=true` 时注册；普通 Worker 不开放该 Handler。
测试不得把遥测错误伪装成业务成功，也不承诺外部副作用 exactly-once。

以上是 PR #35 已提交的验收实现；仍须以最新提交的完整 Compose CI 结果作为完成证据。

## 可重复告警触发验收

`deploy/observability/prometheus/flow-alert-tests.yml` 是确定性 Prometheus rule evaluation 回归样例，
将 Scheduler 的 `flow_database_metrics_refresh_state` 从 1 改为 2，Collector 的 `up` 从 1 改为 0：
持续两分钟后规则应该发出 warning，恢复到正常状态后应清除。
CI 使用同一 `prom/prometheus:v3.7.3` 运行 `promtool test rules`，
避免仅通过 `promtool check rules` 的语法校验就误称告警可以触发。
此测试属于**规则行为验证**，区别于实际 Collector 停机后的 `scripts/test_telemetry_failure_isolation.sh`。

## 受控 RPC Deadline 真实故障注入

`scripts/test_rpc_timeout_fault.sh` 使用 `FLOW_E2E_RPC_TIMEOUT_FAULT=1` 作为强制护栏，
只在隔离 Compose 中短暂暂停 Metadata Provider 的 JVM PID 1，不关闭 TCP 连接或模拟虚假 RPC 数据。
API 必须在既定超时预算内返回失败，`otryx_rpc_client_timeouts_total` 应随真实调用增加；
脚本无论成功或失败都尝试向 JVM 发送 SIGCONT，恢复后再次验证业务 RPC 返回 200。
**不允许在生产服务使用 SIGSTOP 注入，也不把本测试等同于自动重试覆盖证明。**

## 受控 RPC Retry 真实故障注入

`scripts/test_rpc_retry_fault.sh` 仅在显式设置 `FLOW_E2E_RPC_RETRY_FAULT=1` 的隔离 CI 中执行。
它对健康 Metadata Provider 进行一次受控 SIGKILL，随后通过已鉴权 API 发起真实幂等
`ProjectRpc.listByOwner` 调用，并校验 `otryx_rpc_client_retries_total` 增长。
脚本使用 `trap` 在异常退出时恢复对应 Provider，最终必须看到重新注册后的 HTTP 200。
此次注入不依赖生产配置、不篡改任务业务数据，不能据此声称所有错误类型都会重试。
