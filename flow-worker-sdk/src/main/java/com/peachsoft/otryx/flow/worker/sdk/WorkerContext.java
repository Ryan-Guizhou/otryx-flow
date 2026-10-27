package com.peachsoft.otryx.flow.worker.sdk;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Handler 所见的有界任务上下文，包含协作取消与受控结构化日志，不暴露 RPC 的 leaseToken。 */
public final class WorkerContext {
    private final String taskId;
    private final String runId;
    private final AtomicBoolean cancelled;
    private final WorkerLogSink logSink;

    /** 创建上下文；只供 SDK 内部在领取任务后使用。 */
    WorkerContext(String taskId, String runId, AtomicBoolean cancelled) {
        this(taskId, runId, cancelled, WorkerLogSink.NOOP);
    }

    WorkerContext(String taskId, String runId, AtomicBoolean cancelled, WorkerLogSink logSink) {
        this.taskId = Objects.requireNonNull(taskId);
        this.runId = Objects.requireNonNull(runId);
        this.cancelled = Objects.requireNonNull(cancelled);
        this.logSink = Objects.requireNonNull(logSink);
    }

    /** 返回不可变 Task ID。 */
    public String taskId() { return taskId; }

    /** 返回所属逻辑 Run 或 NodeRun ID。 */
    public String runId() { return runId; }

    /** 已请求取消或当前线程被中断时返回 true。 */
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }

    /** Handler 可定期调用，主动停止耗时操作。 */
    public void checkCancelled() throws InterruptedException {
        if (isCancelled()) throw new InterruptedException("Worker task cancelled");
    }

    /** 添加无结构化字段的日志；内容仍会由 Dispatcher 服务端脱敏和限额。 */
    public void log(String level, String message) {
        log(level, message, Map.of());
    }

    /**
     * 添加受控结构化日志。字段数、字段名和值长度均在本地先限制，服务端再次校验并脱敏。
     *
     * @param level TRACE、DEBUG、INFO、WARN 或 ERROR
     * @param message 日志消息，最多 2048 个字符
     * @param fields 最多 16 个结构化字段
     */
    public void log(String level, String message, Map<String, String> fields) {
        if (!Objects.equals(level, "TRACE") && !Objects.equals(level, "DEBUG")
                && !Objects.equals(level, "INFO") && !Objects.equals(level, "WARN")
                && !Objects.equals(level, "ERROR")) {
            throw new IllegalArgumentException("Unsupported worker log level");
        }
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Log message is required");
        String boundedMessage = message.length() > 2048 ? message.substring(0, 2048) : message;
        Map<String, String> safeFields = Map.of();
        if (fields != null && !fields.isEmpty()) {
            if (fields.size() > 16) throw new IllegalArgumentException("Too many worker log fields");
            java.util.LinkedHashMap<String, String> copy = new java.util.LinkedHashMap<>();
            fields.forEach((key, value) -> {
                if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                    throw new IllegalArgumentException("Invalid worker log field name");
                }
                String fieldValue = value == null ? "" : value;
                copy.put(key, fieldValue.length() > 256 ? fieldValue.substring(0, 256) : fieldValue);
            });
            safeFields = Map.copyOf(copy);
        }
        if (boundedMessage.getBytes(StandardCharsets.UTF_8).length > 8192) {
            throw new IllegalArgumentException("Worker log message exceeds the local memory bound");
        }
        logSink.log(level, boundedMessage, safeFields);
    }
}

@FunctionalInterface
interface WorkerLogSink {
    WorkerLogSink NOOP = (level, message, fields) -> { };
    void log(String level, String message, Map<String, String> fields);
}
