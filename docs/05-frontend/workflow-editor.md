# DAG 工作流编辑器设计（Proposed）

## 画布实体
`Node`：id、type（TASK/CONDITION）、label、handlerKey、parameters、timeout/retry、pos；`Edge`：id、source、target、branch（默认/TRUE/FALSE）。仅服务端持久化的规范化 DAG 结构参与发布哈希，视觉坐标可独立存 UI Metadata。

## 前后端验证边界
前端快速校验连接是否合法、自环/明显环、缺字段、重复节点 ID；后端必须重新检查**全部** DAG 规则、项目配额、handler 版本和条件表达式白名单，不信任画布校验结果。拓扑排序、重放和重试均使用后端权威 DefinitionRevision。

## 交互状态
草稿：`CLEAN -> DIRTY -> SAVING -> CLEAN`；并发编辑冲突：`SAVING -> CONFLICT`，保留本地草稿供差异比较。发布：`DRAFT -> VALIDATING -> PUBLISHED/REJECTED`，Published Revision 不可编辑，新版从旧版复制草稿生成。

## 运行态可视化
每个 Node 叠加 `READY/LEASED/RUNNING/RETRY_WAIT/SUCCEEDED/FAILED/SKIPPED/CANCEL_REQUESTED` 等标签，点击展示每次 Attempt。双击日志不会导致再次执行。过滤节点和拖拽只是 UI 视图，不修改已发布拓扑。

## 条件分支确定性
条件表达式只允许 `equals`, `notEquals`, `exists`, `in`, `and`, `or` 以及有界 JSON Path 字段访问；无 `eval` 或任意脚本。运行时输入/前置输出快照不可变，条件计算结果记录在 Node 事件中。未选中边的节点只在对所有依赖语义完成评估后置为 `SKIPPED`。

## 测试矩阵
循环检测、重复边、缺失 Handler、保存中的断网、并发 Revision 冲突、浏览器返回后草稿丢失保护、R1 正在运行期间发布 R2、键盘节点选取/删除、100 节点（压测配置，具体限额待冻结）画布流畅度。
