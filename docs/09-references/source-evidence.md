# 已验证来源、设计假设与实施前核对

**检查日期：2026-10-28**。以下“Current”是从公开 GitHub 现有仓库文件取得的事实；不是 OTRYX Flow 已实现的能力。仓库后来可能变化，M1 必须记录精确 Git SHA 而不是只用 `main`。

| 类型 | 项目事实 | 来源 |
|---|---|---|
| Current | OTRYX RPC `1.0.0-SNAPSHOT`；Java 21、Spring Boot 3.5.4；未在 Maven Central 正式发布 | [OTRYX README](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/README.md) |
| Current | 唯一 `otryx-spring-boot-starter`；Registry Adapter 按需引入；Memory 不用于跨 JVM 发现 | [Starter 文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/reference/starter.md) |
| Current | 应用级注册、Consumer-only 有真实成员无虚构 RPC Endpoint | [Starter 文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/reference/starter.md) |
| Current | `NETWORK_MTLS` 要求实际证书绑定、Codec 能力、受保护 Registry 和 Provider 授权；Nacos 当前不满足 NETWORK/TLS Profile | [安全契约](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/security.md) · [Starter 文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/reference/starter.md) |
| Current | Unary RPC 的 Deadline 不等于业务 Handler 立即停止；重试需明确幂等 | [Deadline 文档](https://github.com/Ryan-Guizhou/otryx-rpc/blob/main/docs/reference/deadline-context.md) |
| Current | peach-scheduler 为基于 Spring Boot + Quartz 的旧轻量调度项目 | [peach-scheduler README](https://github.com/Ryan-Guizhou/peach-scheduler/blob/main/README.md) |
| External | MySQL 8.0 进入 Sustaining Support 阶段，官方建议升级 8.4 LTS 或更高受支持 LTS；新项目建议 MySQL 8.4 LTS | [MySQL 官方 EOL 通知](https://www.mysql.com/support/eol-notice.html) · [MySQL 8.4 LTS 发布模型](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html) |
| External | MySQL 锁读取 `SKIP LOCKED` 适合队列式批次领取，但并非普遍一致视图 | [MySQL 8.4 官方 SELECT](https://dev.mysql.com/doc/refman/8.4/en/select.html) |
| External | Worker 的重试可能导致活动物理执行多次，需幂等处理 | [Temporal Activity 文档](https://docs.temporal.io/encyclopedia/activities/activity-execution) |

## M1 必须核查的前置清单
- [ ] 将 OTRYX RPC 使用版本锁为具体 commit SHA，构建对应 JAR 后进行两进程 Stub/Dispatcher/Schema 兼容 E2E。
- [ ] 验证所有新设计的 `@OtryxRpcService` / `@OtryxRpcReference` 标注、DTO Allowlist 与编解码真实可用。
- [ ] 检查联网 mTLS/Etcd 控制面安装、证书身份/授权、异常路径；不通过不允许公网部署。
- [ ] 新建仓库、设置 License、Branch Protection、Environments、Actions 最小权限和自动化状态。

## 命名规则
`Proposed` 表示设计决策或将来实现；`Current` 表示来源仓库已读事实；`Evidence` 表示带 Commit/工具版本和原始日志的执行证明。不可用 GitHub CI smoke 替代正式固定硬件性能证据。


## 2026-10-28 可观测性集成基线

- OTRYX RPC 仍使用 `1.0.0-SNAPSHOT`、JDK 21 和 Spring Boot 3.5.4。
- Flow 的构建与 Compose 验收固定到 OTRYX RPC commit `0addd7df17c5ba4a0b77e2d30e4c7c75d7ac31f2`，该提交包含 Issue #180 的 OpenTelemetry 自动配置顺序修复；不能仅根据依赖存在就宣称跨 JVM Trace 已通过。
- 运行验收必须确认同一调用的 RPC client/server Span 具有相同 Trace ID，并且 server Span 的 Parent Span ID 等于 client Span ID。证据来自精确 PR Head 的 Collector 输出和 CI 日志。
- 该 SHA 对应已合并的上游 OTRYX RPC PR #181。Flow PR #31 的严格证据校验器检查不同服务的 OTRYX RPC client/server Span 具有相同 Trace ID、server Parent Span ID 指向 client Span ID，且 method ID 一致。
