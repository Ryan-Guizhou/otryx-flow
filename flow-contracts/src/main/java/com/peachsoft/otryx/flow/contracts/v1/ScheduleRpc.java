package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * Scheduler 的应用级 RPC 业务契约；修改操作由数据库约束保证幂等或 CAS。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcContract
public interface ScheduleRpc {
    /** 通过稳定业务请求键创建计划；发布版本必须经 Metadata 校验。 */
    @OtryxRpcIdempotent
    ScheduleMutationReply create(ScheduleCreateCommand command);

    /** 查询项目下的持久计划。 */
    @OtryxRpcIdempotent
    ScheduleListReply list(String ownerUsername, String projectId);

    /** 查询某计划已提交的唯一逻辑火点。 */
    @OtryxRpcIdempotent
    ScheduleTriggerListReply triggers(String ownerUsername, String projectId, String scheduleId);

    /** 启停采用版本 CAS，不进行透明 RPC 重试。 */
    ScheduleMutationReply toggle(ScheduleToggleCommand command);

    /** CAS 修改表达式、时区和绑定版本。已产生的火点保持原样。 */
    ScheduleMutationReply update(ScheduleUpdateCommand command);

    /** 逻辑归档并停止未来触发。 */
    ScheduleMutationReply archive(String ownerUsername, String projectId, String scheduleId, long expectedVersion);
}
