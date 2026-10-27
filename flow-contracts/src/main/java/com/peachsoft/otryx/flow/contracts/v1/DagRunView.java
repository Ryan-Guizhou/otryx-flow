package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DAG Run 固定版本及乐观锁版本；兼容已有只读构造方式。 */
public record DagRunView(String id, String projectId, String workflowId, int workflowRevision,
        String graphSha256, String status, String createdAt, String updatedAt,
        long runVersion) implements Serializable {
    /** 历史单测及兼容调用方采用默认初始版本。 */
    public DagRunView(String id, String projectId, String workflowId, int workflowRevision,
            String graphSha256, String status, String createdAt, String updatedAt) {
        this(id, projectId, workflowId, workflowRevision, graphSha256, status,
                createdAt, updatedAt, 1);
    }
}
