package com.peachsoft.otryx.flow.worker.sdk;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handler 所见的有界任务上下文，包含协作取消，不暴露 RPC 的 leaseToken。 */
public final class WorkerContext {
    private final String taskId;
    private final String runId;
    private final AtomicBoolean cancelled;

    /** 创建上下文；只供 SDK 内部在领取任务后使用。 */
    WorkerContext(String taskId, String runId, AtomicBoolean cancelled) {
        this.taskId = Objects.requireNonNull(taskId);
        this.runId = Objects.requireNonNull(runId);
        this.cancelled = Objects.requireNonNull(cancelled);
    }

    /** 返回不可变 Task ID。 */
    public String taskId() { return taskId; }

    /** 返回所属逻辑 Run ID。 */
    public String runId() { return runId; }

    /** 已请求取消或当前线程被中断时返回 true。 */
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }

    /** Handler 可定期调用，主动停止耗时操作。 */
    public void checkCancelled() throws InterruptedException {
        if (isCancelled()) throw new InterruptedException("Worker task cancelled");
    }
}
