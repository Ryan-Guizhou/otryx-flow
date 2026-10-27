package com.peachsoft.otryx.flow.contracts.v1;
import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;
/** 工作流定义草稿、校验与不可变发布的 RPC 契约（v1）。 */
@OtryxRpcContract
public interface WorkflowRpc {
    /** 读取当前所有者项目的工作流。 */
    @OtryxRpcIdempotent WorkflowListReply list(String ownerUsername, String projectId);
    /** 重试时以请求键保证至多创建一个草稿。 */
    @OtryxRpcIdempotent WorkflowMutationReply createDraft(WorkflowCreateDraftCommand command);
    /** 严格 CAS 更新，不启用自动重试。 */
    WorkflowMutationReply saveDraft(WorkflowSaveDraftCommand command);
    /** 幂等创建不可变版本。 */
    @OtryxRpcIdempotent WorkflowMutationReply publish(WorkflowPublishCommand command);
    /** 按所属项目读取固定版本快照。 */
    @OtryxRpcIdempotent WorkflowRevisionView revision(String ownerUsername,
            String projectId, String workflowId, int revision);
}
