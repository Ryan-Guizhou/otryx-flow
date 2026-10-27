package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Append result for a bounded, idempotent log batch. */
public record DispatchLogBatchReply(String code, int accepted, int duplicates, int dropped)
        implements Serializable { }
