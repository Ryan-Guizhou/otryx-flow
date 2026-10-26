package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Engine 接收终态事件的确定性回执；只有 ACCEPTED/DUPLICATE 允许 ACK Outbox。 */
public record EngineOutcomeReply(String code, String runId, String runStatus) implements Serializable { }
