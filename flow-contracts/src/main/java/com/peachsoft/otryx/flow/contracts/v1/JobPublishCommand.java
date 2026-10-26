package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 任务定义接口数据契约。 */
public record JobPublishCommand(String ownerUsername, String projectId, String jobId, long expectedDraftVersion, String requestKey) implements Serializable {
}
