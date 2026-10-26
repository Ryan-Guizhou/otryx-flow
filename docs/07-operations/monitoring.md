# 运行监控与故障处理指南（Proposed）

## 常见问题定位
1. **Cron 没触发**：检查 Schedule enabled/TZ、`nextFireAt`、`due_lag`、Trigger 唯一键与 Scheduler DB 健康。
2. **Run 已创建不执行**：检查 Engine Outbox、Dispatcher Inbox/dispatchKey、Worker 匹配 Handler/Group/slots、积压与配额。
3. **任务重复执行**：检查租约过期/Worker GC/网络分区、attemptId/epoch、更换 Worker 时是否有业务幂等键。
4. **结果迟迟不更新**：查 Dispatcher Outcome Outbox、Engine Inbox 去重、NodeRun 版本 CAS 冲突。
5. **取消无效**：取消请求是否已发出、Worker 是否确认、中断是否被 Handler 忽略、是否存在外部副作用。
6. **RPC 不可用**：核对实际 app membership、可信目录新鲜度、证书/授权/Codec、Provider 就绪，不把安全拒绝降级重试。
7. **公网白屏**：分别校验 HTTPS、静态路由、REST API 和正确的构建版本；不能仅凭 Web 静态资源 HTTP 200 判断平台可用。

## 操作权限
只读排障与有副作用的重放/终止/修复分开授权。重放 Outbox 必须保持原 eventId，解除 `UNKNOWN_RECOVERY` 需要证据和审计。所有紧急开关默认 Fail Closed，不能绕过鉴权或资源限制。

## 告警/仪表盘计划
参考 [可观测性设计](../03-design/observability.md)。此处尚无 Grafana JSON 仪表盘和告警规则；M4 必须提供真正可导入、可触发和可恢复的规则并标注版本 SHA。
