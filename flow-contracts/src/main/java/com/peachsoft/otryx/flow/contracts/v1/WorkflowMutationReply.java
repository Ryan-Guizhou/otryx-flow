package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 工作流写入状态：OK、NOT_FOUND、CONFLICT 或 INVALID。 */
public record WorkflowMutationReply(String code, WorkflowView workflow, boolean duplicate)
        implements Serializable { }
