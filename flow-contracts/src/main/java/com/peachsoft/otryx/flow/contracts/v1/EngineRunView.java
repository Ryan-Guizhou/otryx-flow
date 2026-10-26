package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 已落库的逻辑执行实例，不表示 Worker 已经实际领取或执行任务。 */
public record EngineRunView(String id, String eventId, String projectId, String scheduleId,
        int scheduleRevision, String jobId, int jobRevision, String plannedFireUtc,
        String origin, String status, String createdAt) implements Serializable { }
