package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Internal-only log batch bound to a single Worker lease generation. */
public record DispatchLogBatchCommand(String workerId, String taskId, long leaseEpoch,
        String leaseToken, DispatchLogEntry[] entries) implements Serializable { }
