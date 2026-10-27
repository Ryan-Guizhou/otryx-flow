package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 已发布不可变 DAG 快照，包含规范化内容 SHA-256。 */
public record WorkflowRevisionView(String workflowId, int revision, String name,
        String graphJson, String graphSha256, long sourceDraftVersion,
        String publishedAt) implements Serializable { }
