package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 要求指定 Worker 在有界时间内停止领取新任务。
 *
 * <p>drainSeconds 的单位是秒，范围 1 到 3600。该命令不中断已经开始的 Handler。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
public record WorkerDrainCommand(String ownerUsername, String workerId, int drainSeconds)
        implements Serializable { }
