# M3 FLOW-015：WorkflowDefinition 与不可变 DAG Revision

**状态：PR #20 待 CI 验收；设计与候选代码相互对应，不代表完整 DAG 执行可用。** 依赖 M2 的已验收短任务链路；后续 FLOW-016 才实现 WorkflowRun / NodeRun 推进。

## 责任边界

- Metadata 自己的 MySQL Schema 存放 flow_workflow_definition 和 flow_workflow_revision，不写其他服务的数据库。
- API 从认证 Session 提取用户名，经 OTRYX WorkflowRpc 传给 Metadata；客户端无权提供 ownerUsername。
- 草稿采用 draftVersion CAS；首次为 1。发布前基于事务 SELECT ... FOR UPDATE，固定图及名称快照，递增 publishedRevision，并将 lastPublishedDraftVersion 固定为本次发布的草稿版本。
- 同一 ownerUsername + createRequestKey 建唯一索引，以规范化图、名称、项目和 owner 的 SHA-256 指纹判定重复/冲突。同一 workflowId + publishRequestKey 唯一；键重放但 draftVersion 不同必须返回冲突。
- graphSha256 精确对应已存的**规范化 graphJson UTF-8 字节**，不包含名称。前端视图坐标、缩放偏好不写入图哈希。
- 发布版本只 INSERT，不 UPDATE；运行时应始终引用固定 Revision。FLOW-015 不会创建 Run、NodeRun 或 Dispatcher Task。

## DAG JSON Schema v1

必须包含 schemaVersion = 1、nodes 数组、edges 数组；禁止未知字段。
- TASK 节点：id、type、可选 label、handlerKey、parameters JSON 对象、timeoutSeconds、maxAttempts。
- CONDITION 节点：id、type、可选 label、condition AST。每个条件必须恰有 TRUE/FALSE 两条出口。
- Edge：id、source、target、branch。TASK 只允许 ALWAYS，CONDITION 只允许 TRUE/FALSE。
- 受限 AST：equals、notEquals、exists、in、and、or，路径为最深 8 段的 $.field 子路径；不支持脚本/反射/SQL/任意 JSONPath 表达式。AND/OR 有界递归，IN 最多 20 项。
- 当前仅允许可信示例 Handler safe.echo 与 safe.fail；实际生产需要发布时由受保护 Handler Catalog 授权。保存字符串不能触发代码执行。

最小有效样例：

~~~json
{
  "schemaVersion": 1,
  "nodes": [
    {
      "id": "start", "type": "TASK", "handlerKey": "safe.echo",
      "parameters": {}, "timeoutSeconds": 30, "maxAttempts": 2
    }
  ],
  "edges": []
}
~~~

## 后端强制边界

| 维度 | 限制 |
|---|---|
| 规范化前图 JSON | UTF-8 最大 48 KiB |
| 节点与边 | 1–100 节点；0–500 边 |
| 节点拓扑深度 | 最大 32 |
| ID | 非空、最大 128 字符、禁 NUL；节点/边 ID 唯一 |
| 参数 | 单节点参数对象最大 16 KiB |
| 执行策略 | 超时 1–86400 秒、最多 10 次尝试 |
| 条件 | AST 深度不超过 6，AND/OR 子项 2–10 |
| 名称 | 非空、长度最多 100 字符 |
| 列表 | 一次最多返回 100 条 |

拓扑顺序采用稳定字符串 ID 优先队列。环、未知节点引用、自环、重复有向边、非法出口、非法参数及未知字段直接拒绝。规范化将对象字段及 nodes/edges 按 ID 排序；有语义的数组顺序不变。

## 写入失败和恢复

- POST create：幂等键未见过返回 201；重复且指纹相同 200；同键不同内容 409。
- PUT draft：只有当前版本可以递增成功；旧版本返回 409，浏览器需刷新后人工合并。
- POST publish：先检查发布键，再检查草稿版本，最后重新验证图并插入 Revision，整个操作同一事务。
- 他人项目读写均按 project + owner 的关联进行过滤；返回 404 或空列表，无需暴露数据存在性。
- 重启 Metadata 后可按项目和版本查询已发布快照；未确认的 RPC 回复不代表写入失败，重试须复用原键。
- R1 正在运行期间发布 R2 不能改变 R1 的定义；运行中版本兼容与效果收敛留给 FLOW-016 与后续滚动部署测试。

## 关联资料

[RPC/REST 合约](../04-api/m3-workflow-contracts.md) · [验收门禁](../06-testing/m3-workflow-acceptance.md) · [原始 Engine 设计](workflow-engine-design.md)。

