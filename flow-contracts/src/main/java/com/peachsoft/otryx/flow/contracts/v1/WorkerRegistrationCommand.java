package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * Worker 心跳上报。handlersCsv 是逗号分隔的已注册 Handler Key，不是在线状态的替代品。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record WorkerRegistrationCommand(String workerId, int capacity, int occupied, String handlersCsv)
        implements Serializable { }
