package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/**
 * 项目内普通 Job Run 的有界列表。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
public record JobRunListReply(JobRunView[] runs) implements Serializable { }
