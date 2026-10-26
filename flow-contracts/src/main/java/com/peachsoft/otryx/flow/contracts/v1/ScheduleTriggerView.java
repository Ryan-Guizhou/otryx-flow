package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 已持久化逻辑火点，PENDING_ENGINE 并不代表业务任务正在运行。 */
public record ScheduleTriggerView(String id, String scheduleId, int scheduleRevision,
        String plannedFireUtc, String status, String createdAt) implements Serializable { }
