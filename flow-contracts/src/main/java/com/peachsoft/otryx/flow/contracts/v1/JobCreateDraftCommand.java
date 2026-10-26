package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 任务定义接口数据契约。 */
public record JobCreateDraftCommand(String ownerUsername, String projectId, String requestKey, String name, String handlerKey, String parametersJson, int timeoutSeconds, int maxAttempts) implements Serializable {
}
