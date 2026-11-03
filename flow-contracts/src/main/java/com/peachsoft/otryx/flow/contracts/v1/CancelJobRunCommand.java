package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 停止一次普通 Job Run 的控制请求。
 *
 * <p>expectedRunVersion 为 0 时不校验版本。同一 requestKey 重放不得产生第二次业务取消。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record CancelJobRunCommand(String ownerUsername, String projectId, String runId, String requestKey,
        long expectedRunVersion) implements Serializable { }
