package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DispatchCancelReply：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
/** CANCELLED: 当前/未来租约失效；TERMINAL: 保留已持久终态；CONFLICT: 身份不匹配。 */
public record DispatchCancelReply(String code, String taskStatus) implements Serializable { }

