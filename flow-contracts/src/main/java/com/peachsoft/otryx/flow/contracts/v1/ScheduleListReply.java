package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 有界计划列表，严格限定到已授权项目。 */
public record ScheduleListReply(ScheduleView[] schedules) implements Serializable { }
