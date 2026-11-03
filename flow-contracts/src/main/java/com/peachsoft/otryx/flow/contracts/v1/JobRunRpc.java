package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 普通 Job 的手动执行、查询、停止和重试。
 *
 * <p>不替代 EngineRpc.acceptScheduledFire。手动运行不得伪装成 Cron 火点。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcContract
public interface JobRunRpc {
    /** 用调用方请求键创建一次 MANUAL 或 RETRY Run。相同正文重放返回原 Run。 */
    @OtryxRpcIdempotent
    JobRunMutationReply startManual(StartManualJobRunCommand command);

    /** 查询项目内最近的普通 Job Run。 */
    @OtryxRpcIdempotent
    JobRunListReply list(String ownerUsername, String projectId);

    /** 查询一条 Run。不存在或不属于该用户时返回 null。 */
    @OtryxRpcIdempotent
    JobRunView detail(String ownerUsername, String projectId, String runId);

    /** 持久化取消意图。已成功或已失败的 Run 不会被倒写成取消。 */
    @OtryxRpcIdempotent
    JobRunMutationReply cancel(CancelJobRunCommand command);
}
