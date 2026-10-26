package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/** 跨 JVM 项目管理 RPC 接口契约。 */
@OtryxRpcContract
public interface ProjectRpc {
    /** 仅查询已认证用户拥有的项目。 */
    @OtryxRpcIdempotent
    ProjectListReply listByOwner(String ownerUsername);

    /** 使用稳定的 ownerUsername 和 requestKey 保证幂等。 */
    @OtryxRpcIdempotent
    ProjectCreatedReply create(ProjectCreateCommand command);
}
