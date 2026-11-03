package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * Metadata 中登记的可信 Handler。在线 Worker 数量由 API 另行汇总，不写进本定义。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record HandlerDefinitionView(String handlerKey, String displayName, String parameterSchema,
        boolean supportsCancel, boolean enabled) implements Serializable { }
