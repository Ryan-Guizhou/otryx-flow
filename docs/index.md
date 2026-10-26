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

M1 implementation note: [Developer Compose](07-operations/m1-developer-compose.md) | [Implemented REST/RPC contract](04-api/m1-implemented-contracts.md) | [M1 evidence status](06-testing/m1-validation-report.md). Source candidate only; local Node/Java DTO offline checks passed, while Maven, Vue bundle, real RPC E2E and public deployment are not yet verified.

M2 FLOW-008 source candidate: [Job REST/RPC contract](04-api/m2-job-contracts.md) | [Job acceptance plan](06-testing/m2-job-acceptance.md). This is not execution or scheduling.
