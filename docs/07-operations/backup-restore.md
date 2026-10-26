# 数据备份、恢复与对账（Proposed）

## 权威源
各服务自己的 MySQL Schema 是任务元数据、触发、Engine 运行和 Dispatcher 租约的持久事实源。Etcd 存储 OTRYX RPC 应用发现/元数据，恢复后必须重新验证实际 Provider/Consumer 注册并触发应用发现刷新。Redis 如引入仅做非权威缓存。

## 备份策略
- MySQL：一致性快照与增量日志策略，根据恢复目标设置频率、保留与加密；数据库账号最小权限，备份加密材料独立保存。
- Etcd：安全快照、恢复前后成员校验；Registry 恢复不能随意清空有活跃业务的服务映射。
- 应用版本：保留不可变 WorkflowRevision/Handler版本、镜像 digest、Flyway 迁移历史。

## 恢复序列
1. 隔离外部入口与新的 Cron 触发，冻结误执行风险，记录事故恢复窗口。
2. 恢复 MySQL/Etcd 到同一已声明的时间点或记录其差异，不伪装精确原子跨库快照。
3. 检查 Flyway 与代码兼容；先启动核心数据服务和单节点 Scheduler/Engine/Dispatcher。
4. 扫描未交付 Outbox、非终态 Run、过期 Lease；逐项对账并记录 `reconcileId`。
5. 对不可确定的非幂等副作用进入 `UNKNOWN_RECOVERY`，不得盲目重试。
6. 启动 Worker、逐步打开新调度与公网入口，检查指标、审计与告警。

## 验收记录
记录备份时间、恢复开始/完成时间、RPO/RTO 实测值、恢复版本、丢失/重复任务清单、人工处理单、数据库校验摘要。M0 尚不能提供已测量的 RPO/RTO 目标。
