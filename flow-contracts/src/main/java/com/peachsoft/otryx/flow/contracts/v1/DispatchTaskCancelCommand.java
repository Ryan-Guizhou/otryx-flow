package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 按 Engine Run 取消物理任务。DAG 仍可使用原 cancelDagTask。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record DispatchTaskCancelCommand(String ownerUsername, String projectId, String runId)
        implements Serializable { }
