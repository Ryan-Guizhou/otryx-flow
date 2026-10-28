package com.peachsoft.otryx.flow.worker.sdk;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCompleteCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchHeartbeatCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogBatchCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogBatchReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogEntry;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;

/**
 * 提供有界任务领取、固定 Handler Allowlist、租约续期、超时与协作取消的纯 Java Worker SDK。
 *
 * <p>Dispatcher 的持久化 Lease/Fencing 是唯一权威；本地超时、取消和 ACK 丢失均不构成远端终态证明。
 * 任务按至少一次语义执行，外部副作用必须具备幂等保护。</p>
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/26 18:20
 */
public final class WorkerRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerRuntime.class);
    private static final Pattern HANDLER_KEY = Pattern.compile("[a-z][a-z0-9_.-]{2,127}");
    private static final int MAX_PARAMETER_BYTES = 16 * 1024;
    private static final int MAX_HANDLER_REGISTRATIONS = 16;
    private static final int LEASE_SECONDS = 30;
    private static final int LOG_QUEUE_CAPACITY = 256;
    private static final int LOG_BATCH_SIZE = 32;
    private static final int MAX_LOG_RETRIES = 3;
    private static final int CLOSE_LOG_FLUSH_ATTEMPTS = 8;
    private static final int SHUTDOWN_AWAIT_SECONDS = 5;
    private static final long DRAIN_POLL_INTERVAL_MILLIS = 30L;
    private static final long LOG_FLUSH_INTERVAL_MILLIS = 250L;
    private final DispatcherRpc dispatcher;
    private final WorkerSettings settings;
    private final Map<String, WorkerHandler> handlers;
    private final Semaphore capacity;
    private final ExecutorService execution;
    private final ScheduledExecutorService control;
    private final ScheduledExecutorService logControl;
    private final ArrayBlockingQueue<PendingLog> logQueue = new ArrayBlockingQueue<>(LOG_QUEUE_CAPACITY);
    private final AtomicLong droppedLogs = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private final AtomicLong cancelledTasks = new AtomicLong();
    private final AtomicLong totalExecutionNanos = new AtomicLong();
    private final ConcurrentHashMap<String, RunningTask> running = new ConcurrentHashMap<>();
    private final AtomicBoolean accepting = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 以显式固定 Handler 映射构建 Worker；拒绝反射、动态类名和不受限的队列。
     *
     * @param dispatcher 通过 OTRYX RPC 获得的真实 Dispatcher 契约
     * @param settings 有界 Worker 参数
     * @param handlers 预先构造的可信 Java Handler 表
     */
    public WorkerRuntime(DispatcherRpc dispatcher, WorkerSettings settings, Map<String, WorkerHandler> handlers) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.settings = Objects.requireNonNull(settings);
        Objects.requireNonNull(handlers);
        if (handlers.isEmpty() || handlers.size() > MAX_HANDLER_REGISTRATIONS) {
            throw new IllegalArgumentException("Worker handler allowlist must be bounded and nonempty");
        }
        Map<String, WorkerHandler> approved = new LinkedHashMap<>();
        handlers.forEach((key, handler) -> {
            if (key == null || !HANDLER_KEY.matcher(key).matches() || handler == null) {
                throw new IllegalArgumentException("Invalid trusted Worker handler registration");
            }
            approved.put(key, handler);
        });
        this.handlers = Map.copyOf(approved);
        this.capacity = new Semaphore(settings.maxConcurrent());
        this.execution = Executors.newFixedThreadPool(settings.maxConcurrent(),
                runnable -> Thread.ofPlatform().name("flow-worker-handler-", 0).unstarted(runnable));
        // Worker 为非 Web 进程；控制线程使用非守护线程，直到调用方显式关闭运行器。
        this.control = Executors.newScheduledThreadPool(2,
                runnable -> Thread.ofPlatform().name("flow-worker-control-", 0).daemon(false).unstarted(runnable));
        this.logControl = Executors.newSingleThreadScheduledExecutor(
                runnable -> Thread.ofPlatform().name("flow-worker-log-", 0).daemon(false).unstarted(runnable));
    }

    /** 启动轮询与定期续租。多次调用 start 不会产生多套线程。 */
    public void start() {
        if (closed.get()) throw new IllegalStateException("Worker runtime is closed");
        if (!accepting.compareAndSet(false, true)) return;
        control.scheduleWithFixedDelay(this::pollSafe, 0,
                settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        control.scheduleAtFixedRate(this::tickSafe, settings.heartbeatInterval().toMillis(),
                settings.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        logControl.scheduleWithFixedDelay(
                this::flushLogsSafe,
                LOG_FLUSH_INTERVAL_MILLIS,
                LOG_FLUSH_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
        LOGGER.info("Worker started: workerId={}, handlers={}, maxConcurrent={}",
                settings.workerId(), handlers.keySet(), settings.maxConcurrent());
    }

    /** 执行单次有界领取轮次；正常运行由独立控制线程周期调用。 */
    public void pollOnce() {
        if (!accepting.get()) return;
        for (Map.Entry<String, WorkerHandler> binding : handlers.entrySet()) {
            int n = Math.min(capacity.availablePermits(), settings.maxConcurrent());
            if (n == 0 || !accepting.get()) return;
            int reserved = 0;
            for (int i = 0; i < n && capacity.tryAcquire(); i++) reserved++;
            if (reserved == 0) return;
            DispatchLeaseView[] leases;
            try {
                DispatchLeaseReply reply = dispatcher.claim(new DispatchLeaseCommand(
                        settings.workerId(), binding.getKey(), reserved));
                leases = reply == null || reply.leases() == null ? new DispatchLeaseView[0] : reply.leases();
                if (leases.length > reserved) {
                    throw new IllegalStateException("Dispatcher returned more leases than available slots");
                }
            } catch (RuntimeException failure) {
                capacity.release(reserved);
                LOGGER.warn("Worker claim failed: workerId={}, errorType={}",
                        settings.workerId(), failure.getClass().getSimpleName());
                return;
            }
            capacity.release(reserved - leases.length);
            for (DispatchLeaseView lease : leases) {
                if (lease == null) {
                    capacity.release();
                    continue;
                }
                String rejectionReason = leaseRejectionReason(lease, binding.getKey(), accepting.get());
                if (rejectionReason != null) {
                    LOGGER.error(
                            "Worker rejected unsafe lease: taskId={}, epoch={}, reason={}",
                            lease.taskId(),
                            lease.leaseEpoch(),
                            rejectionReason);
                    capacity.release();
                    continue;
                }
                RunningTask task;
                try {
                    task = new RunningTask(lease, binding.getValue(), settings.maxTaskDuration());
                } catch (RuntimeException invalidLease) {
                    capacity.release();
                    LOGGER.warn("Worker rejected invalid lease clock: taskId={}, errorType={}",
                            lease.taskId(), invalidLease.getClass().getSimpleName());
                    continue;
                }
                if (running.putIfAbsent(lease.taskId(), task) != null) {
                    capacity.release();
                    LOGGER.error("Duplicate active task rejected: taskId={}", lease.taskId());
                    continue;
                }
                try {
                    execution.execute(() -> execute(task));
                } catch (RuntimeException rejected) {
                    running.remove(lease.taskId(), task);
                    capacity.release();
                    LOGGER.warn("Worker execution rejected: taskId={}, errorType={}",
                            lease.taskId(), rejected.getClass().getSimpleName());
                }
            }
        }
    }

    /** 用于测试的单次续租/超时检查；每次真实上报都携带当前 epoch 和 token。 */
    public void tickOnce() {
        Instant now = Instant.now();
        for (RunningTask task : running.values()) {
            if (task.terminal.get()) continue;
            if (!now.isBefore(task.executionDeadline) || !now.isBefore(task.leaseDeadline)) {
                task.cancelled.set(true);
                Thread thread = task.thread;
                if (thread != null) thread.interrupt();
                if (task.terminal.compareAndSet(false, true)) report(task, "FAILED");
                continue;
            }
            try {
                DispatchMutationReply reply = dispatcher.heartbeat(new DispatchHeartbeatCommand(
                        settings.workerId(), task.lease.taskId(), task.lease.leaseEpoch(),
                        task.lease.leaseToken()));
                if (reply == null || !"OK".equals(reply.code())) {
                    task.terminal.set(true);
                    task.cancelled.set(true);
                    Thread thread = task.thread;
                    if (thread != null) thread.interrupt();
                    LOGGER.warn("Worker lost current lease: taskId={}, epoch={}",
                            task.lease.taskId(), task.lease.leaseEpoch());
                } else {
                    task.leaseDeadline = Instant.now().plusSeconds(LEASE_SECONDS);
                }
            } catch (RuntimeException uncertain) {
                // Unknown RPC response does not renew our local lease certainty.
                LOGGER.warn("Worker heartbeat uncertain: taskId={}, errorType={}",
                        task.lease.taskId(), uncertain.getClass().getSimpleName());
            }
        }
    }

    /**
     * 返回租约校验失败的固定原因码；不会把用户参数或任意输入写入日志。
     *
     * @param lease Dispatcher 返回的租约
     * @param expectedHandlerKey 当前领取时绑定的 Handler 键
     * @param isAccepting Worker 是否仍接受新任务
     * @return 固定原因码，租约可执行时返回 null
     */
    private static String leaseRejectionReason(
            DispatchLeaseView lease,
            String expectedHandlerKey,
            boolean isAccepting) {
        if (!isAccepting) {
            return "worker-not-accepting";
        }
        if (!expectedHandlerKey.equals(lease.handlerKey())) {
            return "handler-mismatch";
        }
        if (lease.parametersJson() == null) {
            return "missing-parameters";
        }
        if (lease.parametersJson().getBytes(StandardCharsets.UTF_8).length > MAX_PARAMETER_BYTES) {
            return "parameters-too-large";
        }
        return null;
    }

    private void execute(RunningTask task) {
        task.thread = Thread.currentThread();
        long startedNanos = System.nanoTime();
        boolean success = false;
        try {
            WorkerContext context = new WorkerContext(task.lease.taskId(), task.lease.runId(), task.cancelled,
                    (level, message, fields) -> enqueueLog(task, level, message, fields));
            context.log("INFO", "Handler execution started", Map.of("handlerKey", task.lease.handlerKey()));
            context.checkCancelled();
            task.handler.execute(task.lease.parametersJson(), context);
            context.checkCancelled();
            success = true;
            context.log("INFO", "Handler execution finished", Map.of("outcome", "SUCCEEDED"));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            enqueueLog(task, "WARN", "Handler execution interrupted", Map.of());
            LOGGER.warn(
                    "Worker handler interrupted: taskId={}, runId={}, epoch={}, handlerKey={}",
                    task.lease.taskId(),
                    task.lease.runId(),
                    task.lease.leaseEpoch(),
                    task.lease.handlerKey());
        } catch (Exception failed) {
            enqueueLog(task, "ERROR", "Handler execution failed",
                    Map.of("errorType", failed.getClass().getSimpleName()));
            LOGGER.warn(
                    "Worker handler failed: taskId={}, runId={}, epoch={}, handlerKey={}, errorType={}",
                    task.lease.taskId(),
                    task.lease.runId(),
                    task.lease.leaseEpoch(),
                    task.lease.handlerKey(),
                    failed.getClass().getSimpleName());
        } finally {
            totalExecutionNanos.addAndGet(Math.max(0L, System.nanoTime() - startedNanos));
            if (success) {
                completedTasks.incrementAndGet();
            } else if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
                cancelledTasks.incrementAndGet();
            } else {
                failedTasks.incrementAndGet();
            }
            if (task.terminal.compareAndSet(false, true)) report(task, success ? "SUCCEEDED" : "FAILED");
            running.remove(task.lease.taskId(), task);
            capacity.release(); // The slot is freed only after the handler body actually exits.
        }
    }

    private void report(RunningTask task, String outcome) {
        try {
            DispatchMutationReply reply = dispatcher.complete(new DispatchCompleteCommand(
                    settings.workerId(), task.lease.taskId(), task.lease.leaseEpoch(),
                    task.lease.leaseToken(), outcome));
            String code = reply == null ? "NULL" : reply.code();
            if (!"OK".equals(code)) {
                LOGGER.warn("Worker completion rejected or stale: taskId={}, epoch={}, code={}",
                        task.lease.taskId(), task.lease.leaseEpoch(), code);
            } else {
                LOGGER.info("Worker completion acknowledged: taskId={}, epoch={}, outcome={}",
                        task.lease.taskId(), task.lease.leaseEpoch(), outcome);
            }
        } catch (RuntimeException uncertain) {
            // Lost acknowledgement leaves an at-least-once ambiguity. Never fabricate success.
            LOGGER.warn(
                    "Worker completion unknown, awaiting lease recovery: taskId={}, epoch={}, outcome={}, errorType={}",
                    task.lease.taskId(),
                    task.lease.leaseEpoch(),
                    outcome,
                    uncertain.getClass().getSimpleName());
        }
    }

    private void pollSafe() {
        try {
            pollOnce();
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Worker polling loop failed: workerId={}, errorType={}",
                    settings.workerId(),
                    error.getClass().getSimpleName());
        }
    }

    private void tickSafe() {
        try {
            tickOnce();
        } catch (RuntimeException error) {
            LOGGER.warn(
                    "Worker heartbeat loop failed: workerId={}, errorType={}",
                    settings.workerId(),
                    error.getClass().getSimpleName());
        }
    }

    /**
     * 返回当前进程持有的执行槽数量，包括正在取消但 Handler 尚未退出的任务。
     *
     * @return 当前运行任务数
     */
    public int activeCount() {
        return running.size();
    }

    /**
     * 返回当前可领取任务的空闲执行槽数量。
     *
     * @return 可用执行槽数
     */
    public int availableSlots() {
        return capacity.availablePermits();
    }

    /**
     * 返回成功退出的 Handler 数量。
     *
     * @return 成功任务累计数
     */
    public long completedTaskCount() {
        return completedTasks.get();
    }

    /**
     * 返回失败退出的 Handler 数量。
     *
     * @return 失败任务累计数
     */
    public long failedTaskCount() {
        return failedTasks.get();
    }

    /**
     * 返回因取消、中断或租约超时而退出的 Handler 数量。
     *
     * @return 取消或中断任务累计数
     */
    public long cancelledTaskCount() {
        return cancelledTasks.get();
    }

    /**
     * 返回 Handler 执行耗时的累计纳秒数。
     *
     * @return 执行耗时累计值，单位为纳秒
     */
    public long totalExecutionNanos() {
        return totalExecutionNanos.get();
    }

    /**
     * 返回因本地有界缓冲区满或租约失效而丢弃的日志条数。
     *
     * @return 丢弃日志累计数
     */
    public long droppedLogCount() {
        return droppedLogs.get();
    }

    private void enqueueLog(RunningTask task, String level, String message, Map<String, String> fields) {
        long sequence = task.logSequence.incrementAndGet();
        PendingLog pending = new PendingLog(task.lease.taskId(), settings.workerId(),
                task.lease.leaseEpoch(), task.lease.leaseToken(),
                new DispatchLogEntry(sequence, level, message, Map.copyOf(fields)), 0);
        if (!logQueue.offer(pending)) droppedLogs.incrementAndGet();
    }

    private void flushLogsSafe() {
        try { flushLogsOnce(); }
        catch (RuntimeException failure) {
            LOGGER.warn(
                    "Worker log flush failed: workerId={}, errorType={}",
                    settings.workerId(),
                    failure.getClass().getSimpleName());
        }
    }

    private void flushLogsOnce() {
        List<PendingLog> batch = new ArrayList<>(LOG_BATCH_SIZE);
        logQueue.drainTo(batch, LOG_BATCH_SIZE);
        if (batch.isEmpty()) return;
        Map<LogGroupKey, List<PendingLog>> groups = new LinkedHashMap<>();
        for (PendingLog item : batch) {
            groups.computeIfAbsent(new LogGroupKey(item.taskId(), item.epoch(), item.token()),
                    ignored -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<LogGroupKey, List<PendingLog>> group : groups.entrySet()) {
            LogGroupKey key = group.getKey();
            List<PendingLog> items = group.getValue();
            DispatchLogEntry[] entries = items.stream().map(PendingLog::entry).toArray(DispatchLogEntry[]::new);
            try {
                DispatchLogBatchReply reply = dispatcher.appendLogs(new DispatchLogBatchCommand(
                        settings.workerId(), key.taskId(), key.epoch(), key.token(), entries));
                if (reply == null || !("OK".equals(reply.code()) || "LIMIT_REACHED".equals(reply.code()))) {
                    droppedLogs.addAndGet(items.size());
                } else if (reply.dropped() > 0) {
                    droppedLogs.addAndGet(reply.dropped());
                }
            } catch (RuntimeException uncertain) {
                for (PendingLog item : items) {
                    if (item.retries() < MAX_LOG_RETRIES) {
                        if (!logQueue.offer(item.withRetry())) droppedLogs.incrementAndGet();
                    } else {
                        droppedLogs.incrementAndGet();
                    }
                }
            }
        }
    }

    /**
     * 标识属于同一租约代际的日志批次；Token 只用于内部 RPC 校验，不写入日志。
     */
    private record LogGroupKey(String taskId, long epoch, String token) {
    }

    /**
     * 本地有界日志队列中的一条记录及其有限重试次数。
     */
    private record PendingLog(
            String taskId,
            String workerId,
            long epoch,
            String token,
            DispatchLogEntry entry,
            int retries) {

        /**
         * 创建重试次数加一的不可变记录。
         *
         * @return 新的待重试日志记录
         */
        private PendingLog withRetry() {
            return new PendingLog(taskId, workerId, epoch, token, entry, retries + 1);
        }
    }

    /**
     * 对指定运行任务发起本地协作取消；不改变 Dispatcher 的租约或持久化终态。
     *
     * @param taskId 要取消的任务标识
     * @return 找到运行任务并发起取消时返回 true，否则返回 false
     */
    public boolean requestCancel(String taskId) {
        RunningTask task = running.get(Objects.requireNonNull(taskId));
        if (task == null) return false;
        task.cancelled.set(true);
        Thread thread = task.thread;
        if (thread != null) thread.interrupt();
        return true;
    }

    /**
     * 停止新领取并在给定期限内等待在途任务退出；超时后请求协作取消。
     *
     * <p>该方法不会强杀忽略中断的业务线程，也不能回滚已经发生的外部副作用。</p>
     *
     * @param timeout 等待任务退出的最长时间
     * @throws NullPointerException timeout 为空
     * @throws IllegalArgumentException timeout 为负数
     */
    public void drain(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Invalid drain deadline");
        accepting.set(false);
        Instant deadline = Instant.now().plus(timeout);
        while (!running.isEmpty() && Instant.now().isBefore(deadline)) {
            try { Thread.sleep(DRAIN_POLL_INTERVAL_MILLIS); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (RunningTask task : running.values()) {
            task.cancelled.set(true);
            Thread thread = task.thread;
            if (thread != null) thread.interrupt();
        }
    }

    /**
     * 关闭 Worker 生命周期资源；重复调用安全。
     *
     * <p>关闭过程会停止领取、尝试排空有界日志并请求取消在途任务，但不保证外部副作用回滚。</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        drain(settings.drainTimeout());
        for (int attempt = 0; attempt < CLOSE_LOG_FLUSH_ATTEMPTS && !logQueue.isEmpty(); attempt++) {
            flushLogsSafe();
        }
        logControl.shutdownNow();
        control.shutdownNow();
        execution.shutdownNow();
        try { execution.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        LOGGER.info(
                "Worker stopped: workerId={}, unfinished={}",
                settings.workerId(),
                running.size());
    }

    /**
     * 保存一个租约执行实例的本地协作状态和期限。
     *
     * <p>此状态只用于本地取消与容量管理，不替代 Dispatcher 持久化状态。</p>
     */
    private static final class RunningTask {
        private final DispatchLeaseView lease;
        private final WorkerHandler handler;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicLong logSequence = new AtomicLong();
        private final Instant executionDeadline;
        private volatile Instant leaseDeadline;
        private volatile Thread thread;

        private RunningTask(DispatchLeaseView lease, WorkerHandler handler, Duration maxDuration) {
            this.lease = lease;
            this.handler = handler;
            this.executionDeadline = Instant.now().plus(
                    Duration.ofSeconds(Math.max(1, Math.min(lease.timeoutSeconds(), (int) maxDuration.toSeconds()))));
            this.leaseDeadline = Instant.parse(lease.leaseUntilUtc());
        }
    }
}
