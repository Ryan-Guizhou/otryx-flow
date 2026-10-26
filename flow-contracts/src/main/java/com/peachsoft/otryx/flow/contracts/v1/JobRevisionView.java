package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 任务定义接口数据契约。 */
public record JobRevisionView(String jobId, int revision, String name, String handlerKey, String parametersJson, int timeoutSeconds, int maxAttempts, long sourceDraftVersion, String publishedAt) implements Serializable {
}
