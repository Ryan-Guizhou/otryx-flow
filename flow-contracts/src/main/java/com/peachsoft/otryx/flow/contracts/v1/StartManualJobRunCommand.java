package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 创建一次手动或重试的普通 Job Run。
 *
 * <p>schedule 字段不在此命令中。重试必须携带新的 requestKey，并指向保留下来的原 Run。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record StartManualJobRunCommand(String ownerUsername, String projectId, String jobId, int jobRevision,
        String requestKey, String parameterOverrideJson, String retryOfRunId) implements Serializable { }
