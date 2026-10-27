# M3 FLOW-016：DAG 运行时依赖推进（进行中）

Issue [#21](https://github.com/Ryan-Guizhou/otryx-flow/issues/21) 的早期实现。**当前只包含纯计算依赖推进器和单元测试，不代表分布式 DAG 已可运行。**

## 已交付的候选基础能力

- 从 FLOW-015 不可变 WorkflowRevision 读取固定 graphJson 与 graphSha256，强制比对 SHA-256，检查无环及显式资源边界。
- 不依赖 JDBC/RPC 的 DagDependencyPlanner，按稳定节点 ID 拓扑顺序提出 BLOCKED、READY、SUCCEEDED、SKIPPED 状态变化。
- 条件节点仅计算有限的 equals、notEquals、exists、in、and、or JSON 字段比较，使用 Run 创建时的不可变输入快照。
- 未选分支 SKIPPED；并行节点可同时 READY，但汇聚节点需等待全部父节点 SUCCEEDED 或 SKIPPED。
- 单次 READY 节点数有界，发现 FAILED 后停止新增派发；测试覆盖菱形 DAG、分支、限额与失败。

## 还必须完成

Engine 自有的 WorkflowRun/NodeRun 持久化与事务、节点 Outbox/Inbox、Dispatcher 节点级唯一任务身份及租约、真实 Worker 终态回传、服务重启与丢失 ACK 恢复、REST 进度与完整 Nacos/MySQL E2E。现有 M2 Dispatcher 的 UNIQUE(engine_run_id) 与 Engine 终态 Inbox 的同 Run 唯一约束必须改造或隔离，不能直接用一个 Engine Run 代表所有节点。

此阶段只建立可测试的依赖推进语义，不提供外部执行 API；完整验收条件以 [Issue #21](https://github.com/Ryan-Guizhou/otryx-flow/issues/21) 为准。

