# M3 FLOW-015（Issue #19 / Draft PR #20）验收清单

**状态：尚未宣称验收完成。** 只有最新 PR Head 对应的 Java、Vue/Node、静态和文档，以及真实 Nacos 3.2.4 + MySQL + OTRYX RPC Compose 集成检查全部通过后方可标记 Issue 完成并合并 main。

## 追踪

- 需求 FR-009、FR-010、FR-011；AT-05（工作流 DAG）；AT-02（项目隔离）；AT-09（版本及幂等恢复边界）。
- Flyway：flow-metadata/src/main/resources/db/migration/V3__workflow_definition.sql
- Java 单测：WorkflowDagValidatorTest、WorkflowDefinitionGraphTest、WorkflowRpcServiceTest、WorkflowControllerTest
- Node 合约：scripts/test_frontend_api.mjs
- Compose E2E：scripts/test_m1_e2e.sh 调用 scripts/test_m3_workflow_e2e.sh（FLOW_E2E_M3_WORKFLOWS=1）

## 必需门禁

| 检查 | 必须证明 |
|---|---|
| Maven Java 21 | 完整 clean verify；可重现规范化哈希、DAG 环/分支、异常输入、事务幂等 |
| Node / Vue | TypeScript 构建及请求键、版本 CAS、项目路径合约 |
| Source / Docs | 结构、依赖、双语段落、链接及需求映射 |
| 真实 Compose | REST → 独立 OTRYX RPC → Metadata MySQL；不以 mock 代替 |
| 幂等创建 | 同键同正文最多一个定义；不同正文 409 |
| 图校验 | 环、未知引用、自环、重复边、禁用 Handler、越界输入拒绝 |
| CAS + 发布 | 草稿旧版本 409；发布键重放只创建一个版本；改版本重放冲突 |
| 不可变快照 | R1 发布后创建 R2，R1 图与 SHA 不变；数据库恰有两条快照 |
| 安全 | session 401、CSRF 403、其他项目不可枚举；写接口 ADMIN 授权 |
| 故障恢复 | Metadata 进程重启后 R2 仍可经 REST → RPC 按版本读取 |
| 审计证据 | 每道 GitHub Action 与同一 Head SHA 对齐；失败不合并 |

## 排除项

本 Issue 只定义、校验、保存并发布工作流。并行节点推进、条件运行、取消/重试、Vue Flow 画布、任意外部代码及公网 mTLS 的验收属于 FLOW-016、FLOW-017、FLOW-018 和 M4/M5。不能以本 PR 的绿色 CI 宣称完整 DAG 实际执行成功。

