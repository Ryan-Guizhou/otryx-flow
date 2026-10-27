# M3 FLOW-017：DAG 取消、逻辑重试与终态竞态

**状态：PR #24 候选设计，验收以前不可宣称生产可用。** 依赖已合并的 FLOW-016，沿用 Engine 私有 MySQL、Nacos 3.2.4 与 OTRYX RPC。

## 状态机

| 起点 | 事件 | 结果 | 永久不允许 |
|---|---|---|---|
| RUNNING | 成功汇聚 | SUCCEEDED | 再取消或重试同一已成功节点 |
| RUNNING | 节点最终失败 | FAILED | 覆盖节点终态 |
| RUNNING | 版本匹配的 Cancel | CANCEL_REQUESTED | 再派发新 READY 节点 |
| CANCEL_REQUESTED | 所有节点已真实终态或 Dispatcher 取消已确认 | CANCELLED | 以未经确认的请求伪装已取消 |
| FAILED | 有界人工 Retry、版本匹配，且无在途任务 | RUNNING | 修改旧 FAILED NodeRun |
| SUCCEEDED、CANCELLED | 任意控制动作 | CONFLICT | 复活已终结 Run |

节点状态：BLOCKED、READY、DISPATCHED、SUCCEEDED、FAILED、SKIPPED，以及 FLOW-017 的 **CANCEL_PENDING/CANCELLED**。已完成的 SUCCEEDED/FAILED NodeRun 永不可覆盖；Worker 成功与取消竞态时，**先持久提交的事实优先**：若 Dispatcher 已持久 SUCCEEDED，它的终态 Outbox 将继续交付；Engine 在 CANCEL_PENDING 状态仍可记录真实 SUCCEEDED，父 Run 最终可为 CANCELLED，但节点必须保留真实 SUCCEEDED 历史。

## 取消的双重栅栏

1. API 从 Session 获取 owner，必须提供 Idempotency-Key 和预期 runVersion。Engine 在持有父 DAG Run 行锁的事务中写入 CANCEL_REQUESTED、控制审计、所有 BLOCKED 节点 CANCELLED、READY/DISPATCHED 节点 CANCEL_PENDING、持久 Cancel Outbox，并撤销仍 PENDING 的节点派发 Outbox。
2. Engine 独立 CancelRelay 领取最多 8 条 Outbox，使用独立 claimToken/45s 租约。在数据库事务**外**通过 OTRYX Dispatcher RPC 执行 `cancelDagTask`。
3. Dispatcher 基于 `flow_dispatch_task.engine_run_id` 唯一键实现持久取消栅栏。若尚未收到 enqueue，先写不可领取的 CANCELLED 占位任务；若已有 READY/LEASED 任务，原子置 CANCELLED、清除 leaseToken、递增 leaseEpoch，并把已持久领取的 Attempt 标为 CANCELLED；若任务已 SUCCEEDED/FAILED，保留终态并返回 TERMINAL。
4. Worker 后续 heartbeat/complete 返回 STALE，本地 Worker SDK 会执行协作中断；**外部副作用无法保证回滚**。收到 Dispatcher CANCELLED ACK 后，Engine 再将 NodeRun CANCELLED。收到 TERMINAL 时等待原终态 Outbox，不伪造取消成功。
5. 若取消 RPC ACK 丢失，稳定 NodeRun ID 重放仍得到相同 Dispatcher 栅栏。临时异常使用有界指数退避；超过 24 次或身份冲突隔离为 DEAD 并告警，保持 CANCEL_REQUESTED 可诊断状态，绝不假报 CANCELLED。

仅隔离开发网络采用可信内部 RPC；生产服务间调用认证与 mTLS 属于 M4，不能把本阶段的内网信任直接暴露公网。

## 人工逻辑重试与内部 Worker 重试分离

- Dispatcher Worker 的尝试数由原有 `maxAttempts` 和 leaseEpoch 控制，**不**增加逻辑 NodeRun。
- 人工重试只允许 **FAILED Run**，且没有 READY/DISPATCHED/CANCEL_PENDING 在途节点，当前恰有一个失败的受信 TASK（目前只允许内置示例 safe.echo/safe.fail）。使用预期 runVersion CAS、唯一控制请求键、失败后至少 2s 退避、每个 nodeId 最多 3 次 logicalAttempt。
- 每次人工重试插入**新** NodeRun UUID 与递增 logicalAttempt，旧 FAILED/成功节点结果保持不变；Planner 只读取每个 nodeId 的最新一次逻辑尝试，避免旧失败阻断新任务。
- 重试新实例复用同一 WorkflowRevision 和不可变输入，不执行任意用户脚本，也不修改旧 Dispatcher Task/Outbox/Outcome Inbox；旧终态 ACK 重放只能影响旧 NodeRun 的去重记录。
- 使用请求键 (ownerUsername, requestKey) 全局唯一，记录 fingerprint、源/目标版本、节点尝试 ID。相同请求重放返回 DUPLICATE，不同正文/预期版本返回 CONFLICT。所有审计仅在 Engine 同一事务提交。

## 并发/隔离与可观测性

Engine 在控制动作、Worker Outcome 和 Cancel ACK 中均先锁父 Run，再更新节点表。令已提交事务的先后决定状态优先级，防止覆盖、双派发或交叉项目串扰。读接口仅返回 Session 归属用户的项目历史，禁止暴露 leaseToken、请求键和指纹。取消 ACK 延迟使用 `flow.dag.cancel.ack.latency` 计时，DEAD Outbox 使用 `flow.dag.cancel.dead.total` 计数；完整生产告警接入 M4。

测试详见[验收](../06-testing/m3-dag-control-acceptance.md)及[API/RPC](../04-api/m3-dag-control-contracts.md)。

