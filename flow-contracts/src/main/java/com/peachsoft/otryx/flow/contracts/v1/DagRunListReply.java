package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** 当前项目的有界 DAG Run 列表。 */
public record DagRunListReply(DagRunView[] runs) implements Serializable { }
