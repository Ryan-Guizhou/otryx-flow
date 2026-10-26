package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 已持久化调度配置及其当前游标；nextFireUtc 为真正 UTC 时刻。 */
public record ScheduleView(String id, String projectId, String jobId, int jobRevision,
        String expression, String timeZone, String misfirePolicy, boolean enabled,
        long version, String nextFireUtc) implements Serializable { }
