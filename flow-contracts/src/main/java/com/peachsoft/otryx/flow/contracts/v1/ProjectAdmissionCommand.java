package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 更新一个项目的入队配额、并发策略和终态保留天数。
 *
 * <p>并发策略只接受 ALLOW、FORBID、QUEUE。控制面写失败时调用方必须视为拒绝，不能放大并发。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
public record ProjectAdmissionCommand(
        String ownerUsername,
        String projectId,
        int maxInflight,
        int maxQueued,
        String concurrencyPolicy,
        int retentionDays) implements Serializable { }
