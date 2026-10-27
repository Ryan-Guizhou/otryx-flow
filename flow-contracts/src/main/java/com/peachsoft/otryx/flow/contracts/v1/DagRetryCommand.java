package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DagRetryCommand：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
public record DagRetryCommand(String ownerUsername, String projectId, String runId,
        String nodeId, long expectedRunVersion, String requestKey) implements Serializable { }

