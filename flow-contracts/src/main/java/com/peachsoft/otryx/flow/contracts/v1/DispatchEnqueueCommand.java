package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Dispatcher 稳定 RPC DTO；跨服务消息不应依赖可变的 Job 草稿。 */
public record DispatchEnqueueCommand(String runId, String eventId, String ownerUsername, String projectId,
        String scheduleId, String jobId, int jobRevision, String handlerKey,
        String parametersJson, int timeoutSeconds, int maxAttempts) implements Serializable { }
