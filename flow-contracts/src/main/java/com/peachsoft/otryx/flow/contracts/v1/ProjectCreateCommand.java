package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 携带稳定操作标识的项目创建请求，供 RPC 失败后安全重试。 */
public record ProjectCreateCommand(String ownerUsername, String requestKey, String name) implements Serializable { }
