package com.peachsoft.otryx.flow.contracts.v1;
import java.io.Serializable;
/** DagControlReply：M3 DAG 取消与逻辑重试的跨 JVM 稳定 DTO。 */
/** OK / DUPLICATE / CONFLICT / NOT_FOUND / INVALID，包含新的或已存在的 NodeRun。 */
public record DagControlReply(String code, DagRunView run, String nodeRunId, boolean duplicate)
        implements Serializable { }

