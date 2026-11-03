package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * Dispatcher 稳定 RPC DTO。跨服务消息不依赖可变的 Job 草稿，也不回传领取令牌。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record DispatchTaskView(String id, String runId, String projectId, String handlerKey,
        String status, int attempts, int maxAttempts, long leaseEpoch, String leaseUntilUtc,
        String updatedAt, String workerId) implements Serializable { }
