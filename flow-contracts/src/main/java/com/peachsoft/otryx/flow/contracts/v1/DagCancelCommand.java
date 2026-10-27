package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DagCancelCommand：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
public record DagCancelCommand(String ownerUsername, String projectId, String runId,
        long expectedRunVersion, String requestKey) implements Serializable { }

