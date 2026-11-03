package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 用版本 CAS 修改 Cron。已经产生的火点不因这次修改而改写。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record ScheduleUpdateCommand(String ownerUsername, String projectId, String scheduleId, long expectedVersion,
        String jobId, int jobRevision, String expression, String timeZone, String misfirePolicy)
        implements Serializable { }
