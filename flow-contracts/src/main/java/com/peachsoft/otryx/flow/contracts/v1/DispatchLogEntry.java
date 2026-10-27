package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;
import java.util.Map;

/** One sequenced, bounded Worker log event. */
public record DispatchLogEntry(long sequence, String level, String message,
        Map<String, String> fields) implements Serializable { }
