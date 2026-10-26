package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/** 任务草稿和不可变发布版本的 RPC 契约。 */
@OtryxRpcContract
public interface JobRpc {
    /** 查询项目内任务。 */
    @OtryxRpcIdempotent
    JobListReply list(String ownerUsername, String projectId);

    /** 幂等创建草稿。 */
    @OtryxRpcIdempotent
    JobMutationReply createDraft(JobCreateDraftCommand command);

    /** 乐观锁更新草稿，禁止框架透明重试。 */
    JobMutationReply saveDraft(JobDraftUpdateCommand command);

    /** 幂等发布不可变任务版本。 */
    @OtryxRpcIdempotent
    JobMutationReply publish(JobPublishCommand command);

    /** 查询固定的已发布快照。 */
    @OtryxRpcIdempotent
    JobRevisionView revision(String ownerUsername, String projectId, String jobId, int revision);
}
