package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** Engine-owned DAG 运行实例；版本及 SHA-256 只读且不可变。 */
public record DagRunView(String id, String projectId, String workflowId, int workflowRevision,
        String graphSha256, String status, String createdAt, String updatedAt) implements Serializable { }
