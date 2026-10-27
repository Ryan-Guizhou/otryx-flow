package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 当前可编辑工作流草稿及已发布版本指针。 */
public record WorkflowView(String id, String projectId, String name, String graphJson,
        long draftVersion, int publishedRevision, long lastPublishedDraftVersion,
        String updatedAt) implements Serializable { }
