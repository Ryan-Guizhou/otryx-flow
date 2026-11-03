package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 普通 Job 的一次逻辑执行。终态来自 Engine 已提交的状态，不由前端计时器推断。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record JobRunView(String id, String projectId, String jobId, int jobRevision, String origin, String status,
        String controlState, long runVersion, String scheduleId, String retryOfRunId, String failureCode,
        String createdAt, String startedAt, String finishedAt) implements Serializable { }
