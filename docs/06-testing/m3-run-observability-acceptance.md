# FLOW-019 / Issue #27：Run 详情与结构化日志验收

**状态：实现候选。** 必须在最新 PR Head 上通过 Java 21 Maven、前端 Node/TypeScript/Vite、源码契约、文档和真实 Compose 集成后，才能合并并关闭 Issue。

## 自动化门禁

- Backend：`mvn -B -ntp clean verify`，包含 Dispatcher 日志去重、lease fencing、字段/消息脱敏与游标分页单测。
- Frontend：Node API contract tests、Vue TypeScript/Vite build；真实 DAG 面板不能展示模拟数据。
- Source：`python3 scripts/check_m1_source.py`，验证日志迁移、RPC、Worker 有界队列、REST owner scope 与页面接入。
- Docs：`python3 scripts/check_docs.py` 与相对链接检查。
- Compose：Nacos 3.2.4 + MySQL + OTRYX RPC + Worker Quickstart 真实链路；复用 `scripts/test_m3_dag_e2e.sh` 验证 DAG 完成后日志可查询。

## 真实业务验收

1. 通过管理端创建并发布合法 DAG，启动固定 Revision 的 Run；刷新页面后 Run、NodeRun 与 logicalAttempt 仍来自 Engine 数据。
2. 成功节点有 handler start/finish 日志；失败节点有失败日志。日志延迟不影响 Run/NodeRun 终态。
3. 对同一 `taskId + leaseEpoch + sequence` 重放批次，数据库只保留一份记录；旧 lease generation 在重新领取后上报返回 STALE。
4. 日志消息包含 `password=top-secret` 或 Bearer token 时，读取内容不得包含原始秘密；`accessToken`、`credential` 等字段值必须变为 `[REDACTED]`。
5. 以 limit=1 读取两条以上日志，第一页面返回 nextCursor，下一页无重复 ID；超长/无效 cursor 返回 400。
6. 验证 1000 条/attempt 预算、本地 256 条队列与 32 条批次上限；日志上报失败不能阻止 Worker heartbeat/complete。
7. 匿名访问返回 401，CSRF 失败返回 403，跨项目 Run/日志读取返回 404；只读角色看不到取消/重试按钮且后端拒绝控制操作。
8. Run 取消保持 CANCEL_REQUESTED 直到权威终态；逻辑重试创建新 NodeRun ID，旧 Attempt 和审计历史不被覆盖。
9. 14 天保留任务每次最多删除 10,000 条过期日志，不删除 Run/NodeRun、Attempt、Inbox/Outbox 状态。

## 合并标准

所有门禁必须对应同一最新 Head；真实 Compose 回归通过后 squash merge main 并关闭 [Issue #27](https://github.com/Ryan-Guizhou/otryx-flow/issues/27)。若集成失败，记录失败步骤与日志，先修复后再重跑最新 Head；不得以轻量门禁替代真实 RPC 证据。
