package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Dispatcher 稳定 RPC DTO；跨服务消息不应依赖可变的 Job 草稿。 */
public record DispatchTaskView(String id, String runId, String projectId, String handlerKey,
        String status, int attempts, int maxAttempts, long leaseEpoch, String leaseUntilUtc,
        String updatedAt) implements Serializable { }
