package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DagControlAuditView：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
/** 只读审计事件：请求键不向浏览器暴露，时间和版本用于安全检查。 */
public record DagControlAuditView(String action, String nodeId, long oldVersion, long newVersion,
        String nodeRunId, String createdAt) implements Serializable { }

