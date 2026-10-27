
# DAG 工作流编辑器（FLOW-018）

**当前实现：Vue 3 + TypeScript + \`@vue-flow/core\`。** 该页面调用已有 Workflow REST API；所有业务持久化与不可变 revision 均由 Metadata 经 OTRYX RPC 管理。当前 PR 的验收状态以 [FLOW-018 验收计划](../06-testing/m3-workflow-canvas-acceptance.md)及最新 CI 为准。

## 用户界面

- 项目级工作流列表、创建草稿、Task/Condition 节点、Edge 连接/分支、Node 属性 Inspector、结构验证、保存、发布、revision 查看、克隆 revision 为新草稿。
- Task handler 固定为 \`safe.echo\` / \`safe.fail\`；参数为 JSON object。Condition 使用受限 AST，支持 equals、notEquals、exists、in、and、or，不执行用户脚本。
- Canvas 提供缩放、平移、拖动、键盘快捷键与本地布局恢复；Node/Edge Inspector 的修改需要应用到画布后才成为待保存领域图。
- 只读角色可以浏览图和不可变 revision；写入和运行按钮只对 ADMIN 展示，服务端权限检查仍然有效。

## 持久化契约

Schema v1 的唯一持久结构为 \`schemaVersion/nodes/edges\`。节点只保存领域字段，边只保存 \`id/source/target/branch\`。Canvas 的 position、selected、VueFlow data、marker 和标签渲染信息仅存在 UI state/localStorage，不进入 graphJson 或 SHA-256。

保存以 \`expectedDraftVersion\` 做 CAS。409 冲突保留本地 JSON 并展示远端 JSON；用户显式选择加载远端或 rebase 本地内容后再次保存。发布后 revision 为只读快照；编辑产生新草稿版本，再发布生成新 revision。运行入口固定 workflowId/revision 并复用 DAG Run Idempotency-Key。

## 快捷键

- \`N\`：添加 Task
- \`Shift+N\`：添加 Condition
- 方向键：按节点列表顺序选择节点
- \`Delete\` / \`Backspace\`：删除当前选中的节点或边

快捷键仅在画布焦点内生效，不会拦截 Inspector 输入框的按键。详细状态机、限制和验收步骤见[设计](../03-design/m3-workflow-canvas.md)与[验收](../06-testing/m3-workflow-canvas-acceptance.md)。
