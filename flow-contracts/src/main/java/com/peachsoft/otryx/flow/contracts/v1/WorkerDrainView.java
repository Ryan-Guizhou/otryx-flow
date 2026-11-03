package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 一个 Worker 停止领取新任务的截止时间。
 *
 * <p>已持有的租约不会被这条记录撤销。截止时间使用 UTC 的 ISO-8601 文本。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
public record WorkerDrainView(String workerId, String drainUntil) implements Serializable { }
