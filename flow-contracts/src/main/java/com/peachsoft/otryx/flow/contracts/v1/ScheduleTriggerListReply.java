package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 调度火点的有界历史快照。 */
public record ScheduleTriggerListReply(ScheduleTriggerView[] triggers) implements Serializable { }
