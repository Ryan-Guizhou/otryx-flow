
# FLOW-018 / Issue #25：工作流画布验收

**状态：PR 验收候选；未通过最新 Head 的全部门禁前不得关闭 Issue。**

| 门禁 | Definition of Done |
|---|---|
| Backend | Java 21 \`mvn -B -ntp clean verify\`，现有 WorkflowController / Metadata Schema 测试通过 |
| Frontend | Node 22 API/图验证测试、Vue TypeScript 检查与 Vite build 全部通过 |
| Source | \`python3 scripts/check_m1_source.py\` 验证 Vue Flow 接入和领域图序列化边界 |
| Docs | \`python3 scripts/check_docs.py\`，中英文 README marker、链接和新设计/验收文档通过 |
| Compose | 真实 Nacos 3.2.4 + MySQL + OTRYX RPC，复用 \`scripts/test_m3_workflow_e2e.sh\` 验证创建、幂等、草稿 CAS、发布、R1 不变、发布 R2、跨项目/CSRF/匿名权限；同时回归 \`scripts/test_m3_dag_e2e.sh\` |

## 前端图验证测试

\`scripts/test_frontend_api.mjs\` 增加纯 Node 图模型测试，覆盖：

- 合法 A→(B∥C)→D diamond 与拓扑布局；
- CONDITION 的组合 AST、TRUE/FALSE 分支要求、安全路径；
- 环、受信 Handler 白名单、非法字段、深度 32 和节点数量上限；
- 序列化时移除 Vue Flow position/data/selected/marker/label，保证这些 UI 属性不进入服务端图。

前端构建还必须验证真实 \`@vue-flow/core\` 组件、事件和样式能够被 Vite 编译。Node 测试不是浏览器可访问性测试，不能宣称替代 Playwright。

## 手工/真实 Compose 业务路径

1. 登录管理员，选择项目，创建合法 Task 草稿。
2. 新增 Task/Condition，拖动布局、连线、选择 Edge branch；图校验不合法时保存和发布被禁用。
3. 保存草稿后版本递增；模拟陈旧版本更新返回 409，确认本地 JSON 保留，并显式选择放弃本地或 rebase 后保存。
4. 发布 r1，查看 SHA-256 与发布时间；切换回草稿编辑、保存并发布 r2；确认 r1 graphJson/hash 完全不变。
5. 只读用户可查看列表/图/revision，但不能保存、发布、克隆或启动 DAG Run；服务端 401/403/404/409 仍为最终裁决。
6. 在 r1/r2 视图运行时，Run 固定引用对应 immutable revision；相同 Idempotency-Key 不创建重复 Run。
7. 运行 M1/M2/FLOW-015/016/017 全量回归，确认现有任务调度、真实 Worker、DAG 取消与重试不受画布依赖影响。

**合并条件：** Backend / Frontend / Source / Docs / Compose 五项在同一 PR Head 全绿；Squash merge main 后关闭 Issue #25。生产 mTLS/HTTPS 与完整 Playwright 是后续阶段，不应被误标为已完成。
