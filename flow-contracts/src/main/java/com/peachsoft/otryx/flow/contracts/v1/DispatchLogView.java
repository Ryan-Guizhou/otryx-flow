package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Redacted persisted execution log returned to an owner-scoped reader. */
public record DispatchLogView(String id, String nodeRunId, String taskId, long leaseEpoch,
        long sequence, String level, String message, String fieldsJson, String createdAt)
        implements Serializable { }
