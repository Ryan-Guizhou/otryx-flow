package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * Dispatcher 观察到的 Worker。status 只来自心跳时间，停领记录不能把离线 Worker 显示为在线。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record WorkerRegistrationView(String workerId, String heartbeatAt, String status, int capacity, int occupied,
        String handlersCsv, boolean draining) implements Serializable { }
