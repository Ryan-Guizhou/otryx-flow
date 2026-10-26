package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 项目创建返回模型，conflict 表示同一幂等键已关联不同请求。 */
public record ProjectCreatedReply(ProjectView project, boolean duplicate, boolean conflict) implements Serializable { }
