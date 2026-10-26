package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** RPC 接口项目数据投影。 */
public record ProjectView(String id, String name, String createdAt) implements Serializable { }
