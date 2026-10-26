package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 创建绑定不可变任务版本的 Cron 计划命令，必须使用稳定请求键。 */
public record ScheduleCreateCommand(String ownerUsername, String projectId, String jobId,
        int jobRevision, String expression, String timeZone, String misfirePolicy,
        String requestKey) implements Serializable { }
