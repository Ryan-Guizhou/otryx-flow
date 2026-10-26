package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** Dispatcher 稳定 RPC DTO；跨服务消息不应依赖可变的 Job 草稿。 */
public record DispatchMutationReply(String code, String taskStatus) implements Serializable { }
