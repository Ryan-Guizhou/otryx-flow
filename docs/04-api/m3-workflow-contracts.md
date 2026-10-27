# M3 FLOW-015：WorkflowDefinition RPC 与 REST 接口

**状态：PR #20 的待验收契约。** Java DTO 在 flow-contracts 的 contracts.v1 命名空间，RPC 接口 WorkflowRpc 版本 1.0.0。生产 mTLS / 授权防护仍需 M4，当前 Compose 是内网隔离的 TRUSTED_DEVELOPMENT。

## REST 路径

| 方法 | 路径 | 权限与语义 |
|---|---|---|
| GET | /api/v1/projects/{projectId}/workflows | 登录用户；当前 owner 项目、最多 100 个 |
| POST | /api/v1/projects/{projectId}/workflows | ADMIN + CSRF + Idempotency-Key；新草稿 |
| PUT | /api/v1/projects/{projectId}/workflows/{workflowId}/draft | ADMIN + CSRF；expectedDraftVersion CAS |
| POST | /api/v1/projects/{projectId}/workflows/{workflowId}/publish | ADMIN + CSRF + Idempotency-Key；不可变发布 |
| GET | /api/v1/projects/{projectId}/workflows/{workflowId}/revisions/{revision} | 登录用户；读取发布快照 |

POST 创建请求包含 name 和 graphJson 字符串。PUT 再包含 expectedDraftVersion；POST 发布仅包含 expectedDraftVersion，版本 UUID 在路径。前端只能发送 Graph v1 序列化 JSON，不允许提交 ownerUsername。详细字段与安全白名单见[设计](../03-design/m3-workflow-definition.md)。

## OTRYX RPC

| 方法 | 合约类型 | 幂等 / 重试说明 |
|---|---|---|
| list(owner, project) | WorkflowListReply | 只读，幂等 |
| createDraft(command) | WorkflowMutationReply | createRequestKey 持久唯一、指纹冲突检测，幂等 |
| saveDraft(command) | WorkflowMutationReply | draftVersion CAS，**不**标记框架透明重试 |
| publish(command) | WorkflowMutationReply | publishRequestKey 唯一 + sourceDraftVersion 比对，幂等 |
| revision(owner, project, id, revision) | WorkflowRevisionView | 只读且按 owner 过滤 |

## 响应语义

- OK：创建 201、同键重放 200、其他成功 200；
- INVALID：400 INVALID_WORKFLOW_REQUEST（非法 Graph JSON、条件 AST、资源上限、禁用 Handler）；
- NOT_FOUND：404 WORKFLOW_NOT_FOUND（不存在或跨项目）；
- CONFLICT：409 WORKFLOW_VERSION_CONFLICT（CAS 或幂等键内容不匹配）；
- 认证 401 / CSRF 和角色拒绝 403，统一沿用 M1 Session 防护。
- 不返回执行证明：此接口只发布 DAG 定义；Run、Attempt 和节点执行由后续 Issue 负责。

## 发布确定性

后端先执行图边界检查及 DAG 拓扑校验，随后排序对象键与顶层节点/边 ID，存为规范化 JSON。修订记录含 graphSha256 = SHA-256(UTF-8 graphJson)、sourceDraftVersion、publishedAt。请求时的重发必须沿用发布键，不能依靠浏览器重建所谓恰好一次保证。

相关[验收清单](../06-testing/m3-workflow-acceptance.md)。

