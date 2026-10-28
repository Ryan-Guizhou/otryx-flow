> **GitHub execution status (2026-10-28):** M1/M2 and FLOW-015/016/017/018/019 are merged; FLOW-019 passed the real Compose gate in [PR #28](https://github.com/Ryan-Guizhou/otryx-flow/pull/28). FLOW-020 is tracked by [Issue #29](https://github.com/Ryan-Guizhou/otryx-flow/issues/29). FLOW-021 [Issue #30](https://github.com/Ryan-Guizhou/otryx-flow/issues/30) is receiving its first observability slice in PR #31; follow-up scope includes HTTP security metrics, RPC metric semantics, and telemetry/database failure injection.

# GitHub Issue 计划索引（部分已创建）

**原则**：一 Issue 一 PR，按依赖拓扑提交；一个 Issue 可包含对应服务的全部源码/测试/迁移/文档，不人为拆成几个只改一个文件的 PR。下表的 `FLOW-xxx` 是计划编号，**不是 GitHub Issue #number**。

| 计划 ID | 里程碑 | Issue 标题 | 关键验收 / 依赖 |
|---|---|---|---|
| FLOW-001 | M0 | 冻结 PRD、ADR、FR/NFR/AT 可追踪基线 | Docs QA；无依赖 |
| FLOW-002 | M1 | 新建独立仓库与 CI 文档门禁 | `check_docs.py` 通过；依赖 001 |
| FLOW-003 | M1 | 固定 OTRYX RPC 提交与双 JVM 跨进程 E2E | 真实应用级发现、超时、错误、身份校验；依赖 002 |
| FLOW-004 | M1 | 初始化 Maven 契约模块、Flyway、服务启动骨架 | JDK21 编译、服务就绪；依赖 003 |
| FLOW-005 | M1 | 构建 Vue3 前端外壳与 REST OpenAPI Client | 前端构建/类型检查/登录占位转真实 API；依赖 004 |
| FLOW-006 | M1 | 实现用户项目、RBAC、会话和审计 | FR-001/002/003/022，AT-02；依赖 004 |
| FLOW-007 | M1 | 增加开发 Compose 与首个跨服务查询业务流 | AT-01/03；依赖 004/006 |
| FLOW-008 | M2 | 实现 Metadata JobDefinition 与不可变 Revision | FR-004、AT-03；依赖 004 |
| FLOW-009 | M2 | 实现 ScheduleDefinition、Cron 解析、TZ/DST 预览 | FR-005/007、AT-04；依赖 008 |
| FLOW-010 | M2 | 实现多 Scheduler 火点唯一领取与 Outbox | FR-007/008、AT-07/09；依赖 009 |
| FLOW-011 | M2 | 实现 Engine createRun 与状态持久化/Inbox | FR-012/019、AT-09；依赖 010 |
| FLOW-012 | M2 | 实现 Dispatcher 持久队列、租约与 Fencing | FR-016/018、AT-08/09；依赖 011 |
| FLOW-013 | M2 | 实现 Worker SDK、主动 Claim、Heartbeat、Complete | FR-015/017/025、AT-08/10；依赖 012 |
| FLOW-014 | M2 | 完成短任务闭环与重复/宕机集成测试 | AT-03/07/08/09；依赖 010-013 |
| FLOW-015 | M3 | 实现 WorkflowDefinition/DAG 后端校验与版本 | FR-009/010/011，AT-05；依赖 008/011 |
| FLOW-016 | M3 | 实现 Engine DAG 依赖推进、并行及条件分支 | FR-012/013，AT-05/09；依赖 015/014 |
| FLOW-017 | M3 | 实现取消、Retry、最终状态竞态收敛 | FR-013/014，AT-10；依赖 016 |
| FLOW-018 | M3 | 实现 Vue Flow 工作流画布/编辑/发布 | FR-009/010/011，AT-05；依赖 005/015；[Issue #25 / PR #26](https://github.com/Ryan-Guizhou/otryx-flow/pull/26) 已合并 |
| FLOW-019 | M3 | 实现 Run 详情、Attempt 进度、日志视图 | FR-019/020，AT-06/12；依赖 013/016/018；[Issue #27](https://github.com/Ryan-Guizhou/otryx-flow/issues/27) 开发中 |
| FLOW-020 | M4 | Nacos 3.2.4 双通道 TLS、mTLS、证书绑定与方法 AuthZ | NFR-001，AT-11；[Issue #29](https://github.com/Ryan-Guizhou/otryx-flow/issues/29)，依赖 OTRYX RPC #179 |
| FLOW-021 | M4 | 接入 OpenTelemetry、业务指标、告警及审计 | FR-021/023/028，AT-12/17；[Issue #30](https://github.com/Ryan-Guizhou/otryx-flow/issues/30)，依赖 014/019 |
| FLOW-022 | M4 | 实现项目配额、Backpressure、Worker Drain | FR-008/015/017，AT-13；依赖 013/020 |
| FLOW-023 | M4 | 实现备份、恢复、对账和历史清理 | FR-026/027，AT-15；依赖 014/017 |
| FLOW-024 | M4 | 限制 Demo Handler、访客权限及敏感输入 | FR-024，AT-16；依赖 006/019/020 |
| FLOW-025 | M5 | 全量两 JVM、DB 及网络 mTLS 故障注入 | AT-07/08/09/10/11；依赖 020/023 |
| FLOW-026 | M5 | 固定环境业务吞吐与 10k RPC 基线对比 | NFR-007，AT-13；依赖 022/025 |
| FLOW-027 | M5 | 契约 Rolling N/N+1 和回滚矩阵 | NFR-008，AT-14；依赖 020/025 |
| FLOW-028 | M5 | 前后端 Playwright E2E、规范与发行验收 | AT-01~17；依赖 018/021/026/027 |
| FLOW-029 | M6 | 实现 GHCR 镜像、受限 HTTPS Demo 部署 | AT-16；依赖 024/028 |
| FLOW-030 | M6 | 执行公开站点业务验收、Release 与运维交付 | AT-01~17、Release Check；依赖 029 |

## 每个 Issue 模板
```text
Title: [M?][FLOW-xxx] <中文可核验目标>
Background / Non-goals:
Requirements: FR-xxx, NFR-xxx
Dependencies: FLOW-xxx
Scope: source, tests, DB migration, docs, FE(if any)
Failure cases: race, timeout, retry, auth, shutdown
DoD: exact commands + evidence path + rollback note + relevant AT
PR: Closes #<GitHub-real-number>
```

导入时把 `FLOW-xxx` 保留在标题中便于追溯，待 GitHub 创建后再替换依赖中的计划号为真实 Issue URL。不要误把上表直接宣称已经在 GitHub 上创建。
