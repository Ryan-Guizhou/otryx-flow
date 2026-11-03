package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 任务定义接口数据契约。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record JobView(String id, String projectId, String name, String handlerKey, String parametersJson,
        int timeoutSeconds, int maxAttempts, long draftVersion, int publishedRevision,
        long lastPublishedDraftVersion, String updatedAt, boolean enabled, boolean archived)
        implements Serializable {
}
