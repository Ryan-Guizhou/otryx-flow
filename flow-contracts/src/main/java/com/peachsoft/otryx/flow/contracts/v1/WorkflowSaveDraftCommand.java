package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 基于版本 CAS 修改工作流草稿的命令。 */
public record WorkflowSaveDraftCommand(String ownerUsername, String projectId,
        String workflowId, long expectedDraftVersion, String name, String graphJson) implements Serializable { }
