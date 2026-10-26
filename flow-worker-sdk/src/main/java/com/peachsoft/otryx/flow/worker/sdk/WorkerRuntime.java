package com.peachsoft.otryx.flow.worker.sdk;

import com.peachsoft.otryx.flow.contracts.v1.DispatchCompleteCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchHeartbeatCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可复用的纯 Java Worker SDK：有界领取、显式 Handler Allowlist、续期、超时与协作取消。
 *
 * <p>Dispatcher 数据库唯一决定 Lease 状态；本地超时、RPC ACK 丢失或取消不构成远端
 * 成功证明。任务可能因租约到期被至少一次重新执行，业务副作用须自行幂等。</p>
 */
public final class WorkerRuntime implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerRuntime.class);
    private static final Pattern HANDLER_KEY = Pattern.compile("[a-z][a-z0-9_.-]{2,127}");
    private static final int MAX_PARAMETER_BYTES = 16 * 1024;
    private static final int LEASE_SECONDS = 30;
    private final DispatcherRpc dispatcher;
    private final WorkerSettings settings;
    private final Map<String, WorkerHandler> handlers;
    private final Semaphore capacity;
    private final ExecutorService execution;
    private final ScheduledExecutorService control;
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
        if (handlers.isEmpty() || handlers.size() > 16) {
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
        // Keep the standalone non-web Worker JVM alive until explicit close().
        this.control = Executors.newScheduledThreadPool(2,
                runnable -> Thread.ofPlatform().name("flow-worker-control-", 0).daemon(false).unstarted(runnable));
    }

    /** 启动轮询与定期续租。多次调用 start 不会产生多套线程。 */
    public void start() {
        if (closed.get()) throw new IllegalStateException("Worker runtime is closed");
        if (!accepting.compareAndSet(false, true)) return;
        control.scheduleWithFixedDelay(this::pollSafe, 0,
                settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
        control.scheduleAtFixedRate(this::tickSafe, settings.heartbeatInterval().toMillis(),
                settings.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        LOGGER.info("Worker started: workerId={}, handlers={}, maxConcurrent={}",
                settings.workerId(), handlers.keySet(), settings.maxConcurrent());
    }

    /** 用于确定性单元测试的单次非阻塞领取；正常运行由内部线程周期调用。 */
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
                if (!accepting.get() || !binding.getKey().equals(lease.handlerKey())
                        || lease.parametersJson() == null
                        || lease.parametersJson().getBytes(StandardCharsets.UTF_8).length > MAX_PARAMETER_BYTES) {
                    // A claimed but untrusted payload is never executed. Expiry will restore the lease.
                    LOGGER.error("Worker rejected unsafe lease: taskId={}", lease.taskId());
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

    private void execute(RunningTask task) {
        task.thread = Thread.currentThread();
        boolean success = false;
        try {
            WorkerContext context = new WorkerContext(task.lease.taskId(), task.lease.runId(), task.cancelled);
            context.checkCancelled();
            task.handler.execute(task.lease.parametersJson(), context);
            context.checkCancelled();
            success = true;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Worker handler interrupted: taskId={}", task.lease.taskId());
        } catch (Exception failed) {
            LOGGER.warn("Worker handler failed: taskId={}, errorType={}",
                    task.lease.taskId(), failed.getClass().getSimpleName());
        } finally {
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
            LOGGER.warn("Worker completion unknown, awaiting lease recovery: taskId={}, errorType={}",
                    task.lease.taskId(), uncertain.getClass().getSimpleName());
        }
    }

    private void pollSafe() {
        try { pollOnce(); }
        catch (RuntimeException error) {
            LOGGER.warn("Worker polling loop failed: errorType={}", error.getClass().getSimpleName());
        }
    }

    private void tickSafe() {
        try { tickOnce(); }
        catch (RuntimeException error) {
            LOGGER.warn("Worker heartbeat loop failed: errorType={}", error.getClass().getSimpleName());
        }
    }

    /** 当前本进程持有但可能尚未完成的执行槽数量。 */
    public int activeCount() { return running.size(); }

    /** 对当前正在运行的 Task 发起本地协作取消；不改变 Dispatcher 的租约或终态。 */
    public boolean requestCancel(String taskId) {
        RunningTask task = running.get(Objects.requireNonNull(taskId));
        if (task == null) return false;
        task.cancelled.set(true);
        Thread thread = task.thread;
        if (thread != null) thread.interrupt();
        return true;
    }

    /** 停止新领取，等待在途任务退出；超时则发出协作取消，不承诺强杀任意业务线程。 */
    public void drain(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative()) throw new IllegalArgumentException("Invalid drain deadline");
        accepting.set(false);
        Instant deadline = Instant.now().plus(timeout);
        while (!running.isEmpty() && Instant.now().isBefore(deadline)) {
            try { Thread.sleep(30); }
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

    /** 生命周期只关闭一次；取消并不能保证已发生的外部副作用回滚。 */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        drain(settings.drainTimeout());
        control.shutdownNow();
        execution.shutdownNow();
        try { execution.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        LOGGER.info("Worker stopped: workerId={}, unfinished={}", settings.workerId(), running.size());
    }

    private static final class RunningTask {
        private final DispatchLeaseView lease;
        private final WorkerHandler handler;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
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
