package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 创建项目范围内工作流草稿的幂等命令。 */
public record WorkflowCreateDraftCommand(String ownerUsername, String projectId,
        String requestKey, String name, String graphJson) implements Serializable { }
