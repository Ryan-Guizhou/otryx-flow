package com.peachsoft.otryx.flow.contracts.v1;

import java.io.Serializable;

/** 任务定义接口数据契约。 */
public record JobListReply(JobView[] jobs) implements Serializable {
}
