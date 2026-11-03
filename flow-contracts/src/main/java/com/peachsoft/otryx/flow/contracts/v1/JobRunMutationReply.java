package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 普通 Job Run 控制结果。HTTP 成功只表示控制指令已接受，不表示 Handler 已执行完成。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record JobRunMutationReply(String code, String runId, String status, boolean duplicate)
        implements Serializable { }
