package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** Engine 创建结果：CREATED、DUPLICATE、CONFLICT、NOT_FOUND、INVALID。 */
public record DagStartReply(String code, DagRunView run, boolean duplicate) implements Serializable { }
