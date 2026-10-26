> M1 source implements `ProjectRpc` for project list/create. This was not yet network-build or E2E validated. [M1 actual contract](m1-implemented-contracts.md). All scheduler/engine/worker RPC contracts below remain **Proposed**.

# OTRYX RPC 业务契约设计（Proposed）

## 现有框架与拟议接口的区分
**框架 Current**：OTRYX RPC `1.0.0-SNAPSHOT` 具备 Spring `@OtryxRpcService` / `@OtryxRpcReference`、应用级注册、Unary RPC；默认安全 Profile 为不允许运行的 `UNSPECIFIED`，必须显式配置。**以下 `Flow*Rpc` 接口名是本项目拟议业务类型，尚未存在于源码。**

共享 Contract 版本 `com.peachsoft.otryx.flow.contracts.v1`；Java 接口保留精确参数/返回值/异常语义。要求 DTO 白名单、字段限额，不传数据库实体/ThreadLocal/凭证/动态 Map/Object。每个方法必须有 `idempotencyKey` 或明确标注不会由框架自动 Retry。

## 服务契约矩阵
| Provider 应用 | 拟议接口 / 方法 | 请求核心字段 | 幂等规则 |
|---|---|---|---|
| `flow-metadata` | `DefinitionQueryRpc.getPublished` | `projectId, definitionId, revision` | 只读，可重试 |
| `flow-scheduler` | `ScheduleCommandRpc.updateSchedule` | `scheduleId, expectedVersion, newSpec` | CAS 版本且请求幂等键 |
| `flow-engine` | `WorkflowRunCommandRpc.createRun` | `triggerKey, definitionRevision, inputHash` | UNIQUE triggerKey，返回同 runId |
| `flow-engine` | `TaskOutcomeSinkRpc.applyOutcome` | `eventId, dispatchKey, attemptId, leaseEpoch, outcome` | Inbox eventId + 节点版本 CAS |
| `flow-dispatcher` | `DispatchCommandRpc.enqueue` | `dispatchKey, handlerKey, payloadRef, group` | UNIQUE dispatchKey，返回同 taskId |
| `flow-dispatcher` | `WorkerPullRpc.claim` | `workerId, workerToken, availableSlots, handlerKeys` | 非幂等，禁止透明自动重试，调用方重试按 requestKey 协议处理 |
| `flow-dispatcher` | `WorkerReportRpc.heartbeat` | `attemptId, leaseEpoch, progress` | Epoch CAS，重复更新有界 |
| `flow-dispatcher` | `WorkerReportRpc.complete` | `attemptId, leaseEpoch, resultDigest` | 仅匹配有效 Epoch；重复同回执可安全复读 |
| `flow-engine` | `WorkflowRunQueryRpc.getRun` | `projectId, runId` | 只读，可重试，授权过滤 |

## RPC 错误/退避
- `AUTH_DENIED`/`IDENTITY_INVALID`/`SCHEMA_INCOMPATIBLE`：安全或契约错误，不绕过、不当成瞬时网络错误重试。
- `DEADLINE_EXCEEDED`：一次 RPC 超时后业务结果是未知，不可直接重新创建不同业务 ID。
- `UNAVAILABLE`/`TRANSIENT_CONTROL_PLANE`：有限预算和指数退避，重放请求必须携带同一个业务幂等键。
- `CAPACITY_REJECTED`：尊重 retryAfter 与 Worker 背压，不能自旋。
- 上述 Flow 业务错误码是建议的应用级 Envelope/错误类型，不意味着 OTRYX 框架已定义同名错误码。

## DTO 设计示例（Java 伪接口，仅规格）
```java
// Proposed, not yet implemented. Validate actual OTRYX annotations/codegen in M1.
public interface WorkflowRunCommandRpc {
    CreateRunReply createRun(CreateRunCommand command);
}

public record CreateRunCommand(
        String triggerKey,
        String projectId,
        String workflowRevisionId,
        String inputDigest) {}

public record CreateRunReply(String runId, boolean duplicate) {}
```

## Deadline 与边界
一次 `claim` / `heartbeat` / `complete` 的 RPC Deadline 采用当前方法剩余预算，**不覆盖任务本身几十分钟的执行时限**；A→B→C 连续 RPC 调用不得刷新父级预算。业务调用线程不执行阻塞的注册中心远程 IO。因为框架源码尚处 SNAPSHOT，正式方法签名、注解、生成 Stub 和序列化行为要在 M1 源码集成测试后冻结。

## 滚动兼容
契约变更要求 `N/N`、`N/N+1`、`N+1/N`、`N+1/N+1`、Rollback 组合 E2E；禁止以 Wire v1 不变来推断 Java 包名和 Schema 可以直接互通。生产使用版本化 Provider 发布与分批升级，旧版本直到在途 Run 结束仍需具备读取旧事件的能力。
