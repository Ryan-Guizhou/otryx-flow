package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 按固定 WorkflowRevision 手动启动 DAG 的幂等命令。 */
public record DagStartCommand(String ownerUsername, String projectId, String workflowId,
        int workflowRevision, String requestKey, String inputJson) implements Serializable { }
