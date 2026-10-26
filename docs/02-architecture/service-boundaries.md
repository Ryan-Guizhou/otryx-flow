# 微服务职责与依赖边界（Proposed）

| 应用 | 写入数据归属 | 对外 RPC 契约（提案） | 必须不做 |
|---|---|---|---|
| `flow-api` | `flow_identity`: 用户、项目成员、审计 | 调用 Metadata、Scheduler、Engine、Dispatcher | 不执行 Handler，不跨服务写库 |
| `flow-metadata` | `flow_meta`: Job/Workflow Definition 与 immutable Revision | `DefinitionQuery`, `DefinitionCommand` | 不直接驱动 Worker |
| `flow-scheduler` | `flow_schedule`: Trigger、Cursor、Outbox | `ScheduleCommand`, `ScheduleQuery`；调用 Engine | 不持有 Run 终态，不在 DB 事务里发 RPC |
| `flow-engine` | `flow_engine`: WorkflowRun、NodeRun、状态事件、Outbox | `RunCommand`, `RunQuery`, `TaskOutcomeSink` | 不直接运行 Java Handler |
| `flow-dispatcher` | `flow_dispatch`: DispatchTask、TaskAttempt、Lease、WorkerPresence、Inbox | `DispatchCommand`, `WorkerPullService`, `WorkerReportService` | 不更改 Engine DAG 节点状态 |
| `flow-worker` | 本地有界日志临时区（可选）；持久状态在 Dispatcher | Consumer：Claim/Heartbeat/Complete；可信 Handler SPI | 不作为分布式数据库、不通过 RPC 连接运行整个任务 |
| `flow-web` | 浏览器临时编辑状态 | 仅 REST API + 可选安全 SSE | 不保存凭据、不直接连接 RPC/数据库 |

### Maven 模块（目标，而非当前目录）
```text
flow-parent/
  flow-bom/
  flow-common/
  flow-contracts/       # 仅接口和精简 DTO, 按依赖方向隔离
  flow-worker-sdk/      # Worker 处理器约定与业务回调
  flow-services/
    flow-api/
    flow-metadata/
    flow-scheduler/
    flow-engine/
    flow-dispatcher/
    flow-worker/
flow-web/
flow-quickstart/
deploy/
```

**唯一 Starter 约束**：OTRYX Starter 只引入业务后端，前端和 Maven DTO 模块不引入 Transport。业务契约 DTO 禁止 Entity/JPA 类型、凭证、MDC、无限集合，以及通用 `Object` 作为未限制载荷。接口版本发生不兼容变化必须添加新 contract 版本和 rolling 测试。

### 明确的一条依赖链
`flow-scheduler -> flow-engine -> flow-dispatcher <- flow-worker`。Dispatcher 通过结果事件调用 `flow-engine`，只引用稳定 `TaskOutcomeSink` 契约，不能形成源代码实现依赖环。循环是运行期事件协作，不是 Maven 模块依赖环。

### 应用身份
所有独立 JVM 用唯一 `spring.application.name`。纯 Consumer Worker 不需要声明虚构的可路由 Provider 端点；OTRYX 注册的是应用成员，Flow 维护的是“能运行哪些 Handler/可用容量”的业务 Worker Presence。具体 Registry 参数从已锁定框架源码读取。
