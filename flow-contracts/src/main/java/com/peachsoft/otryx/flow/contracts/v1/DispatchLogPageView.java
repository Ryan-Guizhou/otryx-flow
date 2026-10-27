package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Cursor-paginated, owner-scoped execution logs. */
public record DispatchLogPageView(DispatchLogView[] items, String nextCursor, int limit)
        implements Serializable { }
