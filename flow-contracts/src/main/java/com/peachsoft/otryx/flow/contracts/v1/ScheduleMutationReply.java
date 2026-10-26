package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 带稳定业务状态码的幂等调度变更回复。 */
public record ScheduleMutationReply(String code, ScheduleView schedule, boolean duplicate)
        implements Serializable { }
