package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Dispatcher 已持久化的终态事件；无需泄露 Worker 的 leaseToken。 */
public record EngineOutcomeCommand(String eventId, String runId, String taskId,
        String projectId, long leaseEpoch, String outcome) implements Serializable { }
