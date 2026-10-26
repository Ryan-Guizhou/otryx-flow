package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 任务定义接口数据契约。 */
public record JobView(String id, String projectId, String name, String handlerKey, String parametersJson, int timeoutSeconds, int maxAttempts, long draftVersion, int publishedRevision, long lastPublishedDraftVersion, String updatedAt) implements Serializable {
}
