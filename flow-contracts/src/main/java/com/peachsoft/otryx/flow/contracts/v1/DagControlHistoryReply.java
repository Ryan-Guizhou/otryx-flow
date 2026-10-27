package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DagControlHistoryReply：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
/** 限定最多 100 条，按创建时间倒序。 */
public record DagControlHistoryReply(DagControlAuditView[] events) implements Serializable { }

