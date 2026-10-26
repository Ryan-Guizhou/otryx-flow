package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/** Dispatcher 入队和 Worker 租约协议；claim/complete 禁止透明重试。 */
@OtryxRpcContract
public interface DispatcherRpc {
    /** Engine 按固定 runId 幂等入队；不执行任务。 */
    @OtryxRpcIdempotent
    DispatchEnqueueReply enqueue(DispatchEnqueueCommand command);

    /** 查询项目任务队列；不公开领取 token。 */
    @OtryxRpcIdempotent
    DispatchTaskListReply list(String ownerUsername, String projectId);

    /** 非幂等 Worker 领取；每次成功领取产生新的 leaseEpoch + token。 */
    DispatchLeaseReply claim(DispatchLeaseCommand command);

    /** 仅当前且未到期的 lease 能续约。 */
    DispatchMutationReply heartbeat(DispatchHeartbeatCommand command);

    /** 仅当前且未到期的 lease 能提交结果；过期代际严禁覆盖。 */
    DispatchMutationReply complete(DispatchCompleteCommand command);
}
