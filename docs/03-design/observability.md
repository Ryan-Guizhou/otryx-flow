# OTRYX Flow 可观测性与诊断

## 当前状态

FLOW-021 / [Issue #30](https://github.com/Ryan-Guizhou/otryx-flow/issues/30) 实施中。当前分支为第一阶段集成：引入 OTRYX RPC 的 Micrometer/OpenTelemetry Adapter、Prometheus registry、内部 Actuator 端口，以及本地 Prometheus/Grafana/OTLP Collector 配置。业务级调度/Run/租约指标、Trace 的真实双 JVM 证据与告警故障注入仍需在本 Issue 后续完成，不能仅凭依赖和配置宣称已验收。

OTRYX RPC 固定基线：706b6506e49e1ade70de9c6c64c844fdd4631e17。注册中心继续使用 Nacos 3.2.4；生产 mTLS/Nacos TLS 由 [FLOW-020 / Issue #29](https://github.com/Ryan-Guizhou/otryx-flow/issues/29) 与上游 [OTRYX RPC #179](https://github.com/Ryan-Guizhou/otryx-rpc/issues/179) 处理。

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

## 待完成验收

- [ ] 真实 Compose 下确认 Prometheus targets、规则加载和 RPC 指标可查询。
- [ ] 真实跨 JVM Trace Context 验收。
- [x] 为 Scheduler、Engine、Dispatcher、Worker 增加权威业务指标的第一批有界快照/运行时指标。
- [ ] 通过真实 Compose 验证 Worker OTLP 指标、RPC Trace Context 和遥测出口故障隔离。
- [ ] 验证遥测出口失败不阻塞任务心跳/完成。
- [ ] 生产 mTLS/Nacos TLS 与公开 HTTPS 仍是独立后续工作。
