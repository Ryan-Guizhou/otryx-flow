
# M3 FLOW-018：Vue Flow 工作流画布设计

**状态：实现候选；以 Issue #25 对应 PR 的最新 Head、五项 CI 和真实 Compose 结果为准。** 本阶段只实现管理端 DAG 编辑与发布，不改变 FLOW-015 的服务端权威验证、FLOW-016 的执行状态机或 FLOW-017 的取消/重试协议。

## 架构与数据边界

\`\`\`mermaid
flowchart LR
  User[管理员或只读用户] --> Canvas[Vue 3 + Vue Flow]
  Canvas --> Local[Local UI layout]
  Canvas --> API[Session / CSRF REST]
  API --> Metadata[WorkflowRpc via OTRYX RPC]
  Metadata --> DB[(MySQL workflow draft and immutable revisions)]
  Canvas --> Engine[Start DAG Run]
  Engine --> Dispatcher[Durable dispatch and Worker lease]
\`\`\`

- 现有 \`WorkflowController\` 是唯一浏览器入口：列表、创建草稿、CAS 保存、发布固定版本和读取指定 revision。浏览器不得直连 Metadata MySQL 或调用内部 RPC。
- 前端通过 \`@vue-flow/core\` 管理拖拽、缩放、平移、连接、选择和边标签；Node/Edge 运行时对象只存在于组件状态。
- 发送给后端的 Schema v1 只有 \`schemaVersion\`、\`nodes\`、\`edges\`。Node 仅含 TASK/CONDITION 的领域字段，Edge 仅含 id/source/target/branch；绝不将 Vue Flow 的 \`position\`、\`data\`、\`selected\`、marker 或其他 UI 状态混入 \`graphJson\` 和 SHA-256。
- 视觉坐标独立保存在浏览器 localStorage，按 project/workflow/draft 或 revision 分区；清除浏览器数据只影响布局，不影响已保存图或发布哈希。默认布局根据拓扑深度生成，拖动后只更新本地布局。
- 当前 Metadata API 没有独立的服务端 UI metadata 字段，因此本阶段不将布局上传到服务端，也不伪装成跨设备同步。

## 图结构与安全约束

| 约束 | 前端反馈 | 服务端最终裁决 |
|---|---|---|
| Schema v1，1–100 个节点、最多 500 条边、48 KiB | 实时验证 | WorkflowDefinitionGraph |
| ID 唯一、引用存在、无自环/重复有向边/环，拓扑深度最多 32 | 阻止保存/发布 | WorkflowDagValidator |
| TASK 仅 safe.echo / safe.fail，参数为不超过 16 KiB 的 JSON object，超时 1–86400 秒，尝试数 1–10 | 字段约束与实时错误 | Handler allowlist + Schema |
| CONDITION 只接受 equals/notEquals/exists/in/and/or、受限 JSON path、标量与有界列表；AST 深度最多 6 | 结构化运算符表单与 JSON AST 输入 | 服务端递归 AST 验证 |
| TASK 的 outgoing edge 必须 ALWAYS；CONDITION 必须各有唯一 TRUE/FALSE 分支 | 自动分配首个缺失分支并校验重复 | 服务端分支校验 |
| Published revision | 明确只读 | 固定 graphJson / SHA-256，不可改写 |

浏览器校验是快速反馈，不是安全边界。任何绕过 UI 的请求仍由 Metadata 服务重新校验；不允许任意 JavaScript、SQL、反射或 shell。

## 编辑、发布和冲突

1. 管理员在项目中创建一个初始合法草稿；只读用户可查看图和 revision，但没有新增/保存/发布/克隆/运行的控制按钮。
2. 画布提供 Task / Condition、连线、边分支编辑、节点属性 Inspector、删除和快捷键：N 新增 Task，Shift+N 新增 Condition，方向键轮选节点，Delete/Backspace 删除当前选中节点或边。输入框中不会拦截这些按键。
3. Node ID 更改时同步更新关联 Edge 的 source/target；领域图重新序列化时只输出白名单字段，并按 ID 稳定排序。
4. Save 使用当前 \`draftVersion\` 做 CAS。409 时保留本地图与远端版本，展示两份 JSON；用户可选择加载远端并放弃本地，或显式 rebase 本地图到远端版本后再次点击保存。Rebase 不自动写数据库，保存时还会确认将覆盖该版本；若版本再次变化，服务端再次返回 409。
5. Publish 仅针对已保存且合法的 draftVersion，生成固定 revision；成功后默认显示只读 revision，展示源 draft 版本、发布时间和 SHA-256。后续编辑仍回到草稿，不能修改已发布快照。
6. Clone revision as draft 使用现有 Create Draft API 新建独立 workflow，保留源 revision 不变；不假定后端存在尚未实现的 clone endpoint。
7. Run 只针对当前选中的 immutable revision 调用 \`startDagRun\`，请求包含固定 workflowId/revision、JSON object input 和稳定 Idempotency-Key。重试网络不确定请求时复用相同 key；改变输入才生成新 key。

## 状态和无损交互

- 加载、空项目、无工作流、无选择、服务错误、只读、无效图、保存中、发布成功和版本冲突均有独立状态。
- 切换项目或工作流会对未保存修改显式确认；节点 Inspector 的未应用编辑也会确认，防止意外丢失。
- HTTP 401 提示重新登录，403 提示权限不足，404 提示资源不存在，409 显示版本冲突，502/503 保留本地状态并允许对比远端。
- Canvas 坐标变化不标记领域图 dirty；节点/边/名字变化才触发保存状态。
- 发布后的运行输入为 JSON object 且最大 16 KiB；执行仍使用既有可信 Handler 与 Worker SDK，不提供任意脚本执行。

## 范围外

完整 Playwright 浏览器矩阵属于 FLOW-028；运行 Attempt 详情和日志时间轴属于 FLOW-019；生产 HTTPS/mTLS、跨设备布局同步、任意脚本、服务端布局元数据均不在本阶段。实现和验收证据见[FLOW-018 验收计划](../06-testing/m3-workflow-canvas-acceptance.md)。
