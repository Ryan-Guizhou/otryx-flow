# 功能详细规格（Proposed）

## 1. 核心聚合
- `Project`：权限和配额作用域。
- `JobDefinition`：一个逻辑任务，`JobRevision` 保存 Handler 标识/参数 Schema/重试与超时策略。
- `ScheduleDefinition`：Cron/时区/误触发策略/并发策略，绑定已发布版本。
- `WorkflowDefinition` / `WorkflowRevision`：一个 DAG 和不可变节点/边快照。
- `WorkflowRun`：逻辑执行；`TaskRun`：一条节点/任务执行；`TaskAttempt`：每次 Worker 实际领取尝试。

## 2. 时间与调度策略
所有执行 `fireAt` 持久化为 UTC；Cron 计算始终使用创建时指定的 IANA 时区。禁止服务器本地时区隐式参与计算。夏令时缺失/重复本地时间必须按固定、被测试的策略产生明确 UTC 触发时间，不能在不同节点上得到不同计划。

- `SKIP`：超出恢复窗口的遗漏火点略过并写审计。
- `FIRE_ONCE`：窗口内多个错过火点合并为一次补触发，记录被合并数量。
- `CATCH_UP`：按原计划时间有界补偿，最大数量/最大回溯时长由项目级配置限制。
- `ALLOW`：可并行多个逻辑 Run，但受项目执行上限约束；`FORBID`：已有活跃 Run 则跳过并留事件；`QUEUE`：持久化排队并有界。

## 3. DAG 语义
入口节点可并行就绪；节点只有在所有必须依赖满足后才入队；首发条件节点使用后端验证的白名单 JSON 比较/布尔组合，禁止脚本执行。未选中的分支节点进入 `SKIPPED`；下游依赖按选中分支计算，避免错误等待。第一次发布对最大节点/边数量进行配置化限制，超过则拒绝，不能放任无界图规模。

首发失败策略 `FAIL_FAST`：不可重试最终失败阻止新下游节点入队；已运行兄弟节点发起协作式取消，最后允许出现真实“晚到成功”但最终工作流仍保留失败事实。手动重试创建新的 Attempt 或新的 Run，不擦掉历史。

## 4. 资源与安全
Worker 申报支持的 Handler、group、label、maxConcurrency；Dispatcher 验证可分配的可信 Handler 版本，针对项目/Worker 双限额准入。系统默认不解析用户输入为脚本或 Java 类名。日志分块长度、JSON 参数体量、Run 时长以及数据保留策略都有明确上限；Demo 强制更严格限制。

## 5. 用户可见的错误状态
UI 必须区别：`PENDING`（排队）、`LEASED`（已领取）、`RUNNING`、`RETRY_WAIT`、`SUCCEEDED`、`FAILED`、`CANCEL_REQUESTED`、`CANCELED`、`TIMED_OUT`、`UNKNOWN_RECOVERY`（恢复需复核）。取消按钮只请求取消；直到 Worker/租约协调确定结果才展示确认终态。
