package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** NodeRun 逻辑尝试历史：不同 logicalAttempt 必须具有不同 ID。 */
public record DagNodeView(String id, String nodeId, String type, String status,
        Boolean conditionDecision, String updatedAt, int logicalAttempt) implements Serializable {
    /** 兼容 FLOW-016 的单尝试消费者。 */
    public DagNodeView(String id, String nodeId, String type, String status,
            Boolean conditionDecision, String updatedAt) {
        this(id, nodeId, type, status, conditionDecision, updatedAt, 1);
    }
}
