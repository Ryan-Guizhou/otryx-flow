package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 一个 WorkflowRun 内稳定的 NodeRun 及其持久状态。 */
public record DagNodeView(String id, String nodeId, String type, String status,
        Boolean conditionDecision, String updatedAt) implements Serializable { }
