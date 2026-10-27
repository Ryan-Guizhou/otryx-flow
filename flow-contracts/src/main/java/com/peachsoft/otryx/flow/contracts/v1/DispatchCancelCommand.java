package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DispatchCancelCommand：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
/** 由 Engine 请求 Dispatcher 原子取消 NodeRun，含本地所有权和 DAG 关联。 */
public record DispatchCancelCommand(String runId, String ownerUsername, String projectId,
        String dagRunId, String workflowId, int workflowRevision) implements Serializable { }

