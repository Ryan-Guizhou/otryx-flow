package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 发布不可变工作流版本的幂等命令。 */
public record WorkflowPublishCommand(String ownerUsername, String projectId,
        String workflowId, long expectedDraftVersion, String requestKey) implements Serializable { }
