package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 一次 Worker 领取。不包含租约令牌。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 15:10
 */
public record DispatchAttemptView(String taskId, long leaseEpoch, String workerId, String status,
        String startedAt, String finishedAt) implements Serializable {
}
