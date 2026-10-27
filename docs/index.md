# 文档总览（M0 Proposed）

当前基线 `v0.1.0 / 2026-10-23`，目标读者是产品负责人、前后端工程师、测试、SRE 与外部贡献者。

| 阅读目的 | 文档入口 | 要回答的问题 |
|---|---|---|
| 为什么做 | [产品定位](01-product/product-vision.md) | 角色、目标、非目标、成功指标 |
| 做什么 | [PRD](01-product/requirements-prd.md) / [用例](01-product/use-cases.md) / [功能细则](01-product/functional-spec.md) | 每个功能何时触发、怎样失败、如何验收 |
| 怎么拆分 | [架构](02-architecture/architecture-overview.md) / [边界](02-architecture/service-boundaries.md) | 真实服务职责、依赖方向 |
| 怎么保证可靠 | [一致性恢复](03-design/consistency-and-recovery.md) / [Scheduler](03-design/scheduler-design.md) / [Engine](03-design/workflow-engine-design.md) / [Dispatcher](03-design/dispatcher-worker-design.md) | 幂等、租约、状态机、失败路径 |
| 数据/API | [数据模型](03-design/data-model.md) / [REST](04-api/rest-api.md) / [RPC](04-api/rpc-contracts.md) | 存储和契约、业务权限 |
| 前端体验 | [交互规范](05-frontend/ui-ux-spec.md) / [页面](05-frontend/page-design.md) / [画布](05-frontend/workflow-editor.md) | 页面、用户操作、校验、失败展示 |
| 交付质量 | [验收计划](06-testing/acceptance-plan.md) / [测试用例](06-testing/test-cases.md) / [性能基线](06-testing/performance-plan.md) | 如何产生可核查测试证据 |
| 如何部署 | [部署拓扑](02-architecture/deployment-architecture.md) / [公网部署](07-operations/production-deployment.md) | 安全边界、CI/CD、上线和回滚 |
| 开发任务 | [里程碑](08-roadmap/milestones.md) / [Issue 清单](08-roadmap/issue-backlog.md) | 工作顺序和 DoD |
| 核对来源 | [基线与证据](09-references/source-evidence.md) | 哪些是已验证的 RPC 事实，哪些仍待开发 |

**文档版本规定：** 所有新设计在具体文档中标注 Proposed；上线后将“已实现”证据链接到 Commit、CI、实际请求与截图，不逆向宣称计划就是事实。


## 已实现与正在开发

M1 已在 [PR #2](https://github.com/Ryan-Guizhou/otryx-flow/pull/2) 验收。M2 定义/调度/Engine/Dispatcher/Worker 与真实终态闭环均已合并（最终 [PR #18](https://github.com/Ryan-Guizhou/otryx-flow/pull/18) 五项 CI 检查通过）；[M2 终态设计](03-design/m2-outcome-reconciliation.md)和[完整验收](06-testing/m2-final-acceptance.md)。

M3 FLOW-015 / [PR #20](https://github.com/Ryan-Guizhou/otryx-flow/pull/20) 已通过 5/5 CI 合并，提供[DAG 定义及不可变修订](03-design/m3-workflow-definition.md)、[RPC/REST](04-api/m3-workflow-contracts.md)。当前 M3 FLOW-016 / [Issue #21](https://github.com/Ryan-Guizhou/otryx-flow/issues/21) / [Draft PR #22](https://github.com/Ryan-Guizhou/otryx-flow/pull/22) 正在实施[Engine DAG 运行时](03-design/m3-dag-runtime.md)、[RPC/REST](04-api/m3-dag-runtime-contracts.md)、[节点级验收](06-testing/m3-dag-acceptance.md)，需真实多 Worker Compose 验收后方可认定完成。Nacos 3.2.4 仍是唯一活跃注册中心；生产 HTTPS/mTLS 未实现。

### FLOW-017：取消、重试及终态竞态（待验收）

[M3 DAG 控制面设计](03-design/m3-dag-control.md)、[REST/RPC](04-api/m3-dag-control-contracts.md)、[真实 Compose 验收](06-testing/m3-dag-control-acceptance.md)分别记录 CANCEL_REQUESTED 可靠栅栏、Run 版本 CAS 和新 NodeRun logicalAttempt。对应 [Issue #23](https://github.com/Ryan-Guizhou/otryx-flow/issues/23)，与已验收的 FLOW-016 节点派发阶段隔离；通过最新 Head 全量 CI 前保持进行中。
