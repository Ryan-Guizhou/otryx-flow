package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 工作流 Run 与节点快照；不包含租约凭据。 */
public record DagRunDetailView(DagRunView run, DagNodeView[] nodes) implements Serializable { }
