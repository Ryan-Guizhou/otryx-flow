package com.peachsoft.otryx.flow.contracts.v1;
import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;
/** M3 手动 DAG 执行、只读节点进度的 Engine RPC 合约。 */
@OtryxRpcContract
public interface DagRpc {
    /** Idempotency-Key 绑定用户、项目、WorkflowRevision 与原始输入快照。 */
    @OtryxRpcIdempotent
    DagStartReply start(DagStartCommand command);
    /** 当前用户可见的 Run 列表。 */
    @OtryxRpcIdempotent
    DagRunListReply list(String ownerUsername, String projectId);
    /** 当前用户可见的节点状态快照。 */
    @OtryxRpcIdempotent
    DagRunDetailView detail(String ownerUsername, String projectId, String runId);
}
