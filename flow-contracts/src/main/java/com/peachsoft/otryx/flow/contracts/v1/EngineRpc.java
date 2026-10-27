package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/** 通过 OTRYX RPC 接收 Scheduler 唯一火点、查询逻辑执行实例。 */
@OtryxRpcContract
public interface EngineRpc {
    /** 同一 eventId+消息内容重复发送不会创建第二个 Run。 */
    @OtryxRpcIdempotent
    EngineFireReply acceptScheduledFire(ScheduleFireCommand event);

    /** 仅返回所给 owner/project 内的执行实例；前端身份由 API 门面校验。 */
    @OtryxRpcIdempotent
    EngineRunListReply listRuns(String ownerUsername, String projectId);

    /** 按 Dispatcher eventId 幂等接收 Worker 终态，事务内同时写 Inbox 与 Run。 */
    @OtryxRpcIdempotent
    EngineOutcomeReply acknowledgeTaskOutcome(EngineOutcomeCommand event);
}
