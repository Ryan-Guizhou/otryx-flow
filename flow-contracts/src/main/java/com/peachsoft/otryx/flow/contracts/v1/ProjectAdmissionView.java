package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 项目级入队配额与并发策略的只读快照。
 *
 * <p>未单独配置的项目使用 Dispatcher 内置默认值。该快照不包含 Worker 租约令牌。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
public record ProjectAdmissionView(
        String ownerUsername,
        String projectId,
        int maxInflight,
        int maxQueued,
        String concurrencyPolicy,
        int retentionDays) implements Serializable { }
