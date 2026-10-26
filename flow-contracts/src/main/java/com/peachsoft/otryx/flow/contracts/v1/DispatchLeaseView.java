package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Dispatcher 稳定 RPC DTO；跨服务消息不应依赖可变的 Job 草稿。 */
public record DispatchLeaseView(String taskId, String runId, String eventId, String projectId,
        String handlerKey, String parametersJson, int timeoutSeconds, int attempt,
        int maxAttempts, long leaseEpoch, String leaseToken, String leaseUntilUtc) implements Serializable { }
