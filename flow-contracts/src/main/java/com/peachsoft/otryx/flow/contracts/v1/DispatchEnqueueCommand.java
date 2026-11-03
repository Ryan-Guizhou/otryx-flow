package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * Dispatcher 稳定 RPC DTO；跨服务消息不应依赖可变的 Job 草稿。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record DispatchEnqueueCommand(String runId, String eventId, String ownerUsername, String projectId,
        String scheduleId, String jobId, int jobRevision, String handlerKey,
        String parametersJson, int timeoutSeconds, int maxAttempts, String targetType) implements Serializable { }
