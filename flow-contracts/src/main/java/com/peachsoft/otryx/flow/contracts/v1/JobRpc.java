package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 任务草稿和不可变发布版本的 RPC 契约。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcContract
public interface JobRpc {
    /** 查询项目内任务。 */
    @OtryxRpcIdempotent
    JobListReply list(String ownerUsername, String projectId);

    /** 查询一条任务，包含已归档定义。不属于该用户时返回 null。 */
    @OtryxRpcIdempotent
    JobView get(String ownerUsername, String projectId, String jobId);

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

    /** 逻辑归档。历史 Run 仍可查询，归档定义默认不再出现在列表中。 */
    JobMutationReply archive(String ownerUsername, String projectId, String jobId, long expectedDraftVersion);

    /** 停用后不能再新建实例。已有实例不在这里被取消。 */
    JobMutationReply setEnabled(String ownerUsername, String projectId, String jobId, boolean enabled,
            long expectedDraftVersion);
}
