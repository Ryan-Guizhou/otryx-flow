# 可靠性、一致性与故障恢复契约（Proposed）

## 必须同时满足的五个不变量
1. **逻辑去重**：唯一调度火点/手动幂等键至多对应一个 `WorkflowRun`。
2. **持久投递**：DB 事务记录状态变化时同事务记录 Outbox；已提交状态不因 RPC 发送失败而永久丢失。
3. **租约代际**：`Attempt+Epoch` 以 DB CAS 才能完成权威状态提交，过期 Worker 的结果不能覆盖新的权威结果。
4. **节点推进**：某节点只有一个逻辑 `NodeRun`；进入下游分发由幂等 dispatchKey + Outbox 约束。
5. **不虚构 Exactly Once**：重复物理执行和不可逆外部效果需要 Handler 幂等或业务人工复核。

## 事务 Outbox + Inbox 协议
```mermaid
sequenceDiagram
  participant A as Owner Service A
  participant DBa as A DB
  participant B as Consumer Service B
  participant DBb as B DB
  A->>DBa: TX: update business state + insert Outbox(eventId)
  A->>B: RPC deliver(eventId,payload)
  B->>DBb: TX: insert Inbox unique + apply domain change
  B-->>A: applied / already_applied
  A->>DBa: mark Outbox delivered
  Note over A,B: Timeout means unknown; retry SAME eventId
```

应使用有限批次、指数退避+抖动、死信队列状态和最大累计失败告警；不能在权限失败时自动回退为允许或绕过 mTLS。服务修复后通过运维受控回放仍应使用原业务幂等键。

## 故障矩阵
| 故障注入 | 权威状态 | 恢复/验收 |
|---|---|---|
| Scheduler 持锁时崩溃 | DB 事务回滚或完整提交 | 新节点领取，不丢火点、不额外创建逻辑 Run |
| A 已提交 Outbox，RPC 不可达 | Outbox `PENDING` | 重投同 eventId，B Inbox 防重复 |
| B 已提交业务但 ACK 丢失 | B DB 权威 | A 重试得到 DUPLICATE，不重复状态变更 |
| Worker A 丢心跳 | Dispatcher leaseEpoch 权威 | 租约过期创建 B 新 Attempt；A 旧结果拒绝 |
| Worker 已执行外部写入但崩溃 | 外部系统可能已经成功 | 稳定幂等键处理或 `UNKNOWN_RECOVERY` 复核 |
| Engine 在并行下游入队中崩溃 | Engine outbox 权威 | 补扫未交付事件与已有 dispatchKey 去重 |
| 网络分区、Etcd 过期目录 | OTRYX 本地可信目录与安全规则 | 有限新鲜度；不能将认证失败错误降级到缓存 |
| 证书过期/授权拒绝 | 安全系统拒绝 | 不自动重试为明文，不分发业务任务 |
| MySQL 不可用 | 最后已提交事务 | Fail Closed、告警、停止发新任务；恢复后对账 |
| 取消与成功同时到达 | 状态 CAS 与真实执行结果 | 不伪造已取消；事件日志说明竞态 |

## 任务级超时与 RPC Deadline 区别
短 RPC 的 Deadline 是一次 `claim/heartbeat/complete` 的网络预算，不能代替业务 Handler `maxRunDuration`。RPC Future 超时不证明 Provider 或 Worker 处理停止；任务业务超时必须由 Worker 与租约监督器独立完成，可能需要人工处理忽略中断的 Handler。

## 数据修复/对账入口
服务按自己 DB 的非终态记录扫描：Scheduler 检查 due cursor/Outbox；Engine 检查未推进 NodeRun；Dispatcher 检查过期 Lease/未交付 TaskOutcome；所有扫描分页、有超时与速率控制，记录 `reconcile_id`，禁止隐藏式无界全表扫描。

## 需保持人工决策的情形
非幂等副作用、长时间任务没有最新 checkpoint、外部系统无法查询最终结果或两种证据相互矛盾时使用 `UNKNOWN_RECOVERY` 并阻断自动重试。提供手动“标记已处理/重试/终止”的审计化恢复入口。
