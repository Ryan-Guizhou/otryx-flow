package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 项目列表返回模型，M1 最多 100 条。 */
public record ProjectListReply(ProjectView[] projects) implements Serializable { }
