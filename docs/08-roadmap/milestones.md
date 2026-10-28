# 里程碑与实施顺序（Proposed）

| 里程碑 | 交付范围 | Gate | 依赖 |
|---|---|---|---|
| M0 规格冻结 | PRD、ADR、服务/数据/接口/前端设计、验收矩阵、仓库治理文档 | 文档检查、评审、风险登记 | 无 |
| M1 可运行骨架 | GitHub 独立仓库、真实 RPC 多 JVM E2E、前端/REST 基础、登录/RBAC、Flyway/Compose | AT-01/02、RPC E2E | M0 |
| M2 可靠调度闭环 | Metadata、Scheduler、Engine 最小 Run、Dispatcher、Worker SDK、Outbox/Inbox/Lease | AT-03/04/07/08/09 | M1 |
| M3 DAG 和完整前端 | Vue Flow DAG 设计器、Revision、条件分支、并行推进、执行历史/日志 | AT-05/06/10 | M2 |
| M4 安全与运维 | Nacos 3.2.4 TLS/mTLS/AuthZ、告警/Trace、备份、错误处理、Demo 安全边界 | AT-11/12/15/17 | M3 |
| M5 综合验证 | 端到端/恢复/滚动兼容、安全/性能/长稳、文档收口 | AT-13/14，全量 P0 | M4 |
| M6 站点与发行 | GHCR、Compose 公网 Demo、HTTPS、验证/回滚、Release、用户指南 | AT-16，release checklist | M5 |

每一里程碑结束时更新 Issue/PR/Commit/CI 原始证据、源码/文档对应版本；不允许以“已计划”替代“已通过”。可在相同里程碑内并行处理低耦合 issue，但服务契约必须先于调用者合入。

## 里程碑冻结判定
M0 文档经评审并拥有需求追踪；M1 可重复跨 JVM 调用和首次登录；M2 故障恢复通过；M3 浏览器运行真实 DAG（FLOW-019 已合并）；M4 网络安全与可观测性实际验收；M5 固定环境性能/恢复/兼容门禁；M6 提供受限 HTTPS 站点并能执行内置流程。
