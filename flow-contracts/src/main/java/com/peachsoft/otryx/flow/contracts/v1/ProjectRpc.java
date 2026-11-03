package com.peachsoft.otryx.flow.contracts.v1;

import com.peachsoft.otryx.api.OtryxRpcContract;
import com.peachsoft.otryx.api.OtryxRpcIdempotent;

/**
 * 跨 JVM 项目管理 RPC 接口契约。项目是否可见只由元数据里的所有者决定。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcContract
public interface ProjectRpc {
    /** 仅查询已认证用户拥有的项目。 */
    @OtryxRpcIdempotent
    ProjectListReply listByOwner(String ownerUsername);

    /** 当前用户是否拥有该项目。不存在和越权都返回 false，避免泄露其他项目。 */
    @OtryxRpcIdempotent
    boolean hasAccess(String ownerUsername, String projectId);

    /** 使用稳定的 ownerUsername 和 requestKey 保证幂等。 */
    @OtryxRpcIdempotent
    ProjectCreatedReply create(ProjectCreateCommand command);
}
