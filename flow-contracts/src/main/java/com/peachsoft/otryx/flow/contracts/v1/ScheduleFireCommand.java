package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * Scheduler Outbox 中的不可变 Cron 火点事件。eventId 是永久幂等键。
 * 每个字段来自已经提交的 Scheduler 业务快照，不得使用当前可变 Job 草稿替代。
 */
public record ScheduleFireCommand(String eventId, String ownerUsername, String projectId,
        String scheduleId, int scheduleRevision, String jobId, int jobRevision,
        String plannedFireUtc, String origin) implements Serializable { }
