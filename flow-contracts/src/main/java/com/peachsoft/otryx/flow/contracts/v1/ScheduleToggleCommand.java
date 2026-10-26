package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 通过版本 CAS 启用或暂停调度；重新启用将从当前时间计算新游标。 */
public record ScheduleToggleCommand(String ownerUsername, String projectId, String scheduleId,
        long expectedVersion, boolean enabled) implements Serializable { }
