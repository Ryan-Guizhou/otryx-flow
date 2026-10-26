package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Engine 接收持久化火点的结果：CREATED、DUPLICATE 或 CONFLICT。 */
public record EngineFireReply(String code, String runId, String runStatus) implements Serializable { }
