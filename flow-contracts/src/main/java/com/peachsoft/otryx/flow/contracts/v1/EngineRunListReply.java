package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 指定所有者、项目下最近的、数量有上限的持久逻辑执行实例。 */
public record EngineRunListReply(EngineRunView[] runs) implements Serializable { }
