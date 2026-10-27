package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 用户可见工作流列表，上限由服务端控制。 */
public record WorkflowListReply(WorkflowView[] workflows) implements Serializable { }
