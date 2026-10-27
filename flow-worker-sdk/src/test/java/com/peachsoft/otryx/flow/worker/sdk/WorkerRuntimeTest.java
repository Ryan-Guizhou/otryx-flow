package com.peachsoft.otryx.flow.worker.sdk;

import static org.junit.jupiter.api.Assertions.*;

import com.peachsoft.otryx.flow.contracts.v1.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证真实 SDK 的领取、显式 Handler、心跳、超时、并发上限与优雅关闭。 */
class WorkerRuntimeTest {
    private static final String WORKER = "sdk-test-worker";

    @Test
    void explicitHandlerRunsAndReportsSuccessWithoutLeakingToken() throws Exception {
        FakeDispatcher rpc = new FakeDispatcher();
        AtomicReference<String> seenTask = new AtomicReference<>();
        AtomicBoolean cancelled = new AtomicBoolean(true);
        WorkerSettings settings = settings(2, 5);
        try (WorkerRuntime runtime = new WorkerRuntime(rpc, settings, Map.of("safe.echo", (payload, ctx) -> {
            seenTask.set(ctx.taskId());
            cancelled.set(ctx.isCancelled());
            assertEquals("{}", payload);
        }))) {
            runtime.start();
            assertTrue(rpc.completed.await(6, TimeUnit.SECONDS));
            assertEquals("SUCCEEDED", rpc.outcome.get());
            assertEquals(rpc.taskId, seenTask.get());
            assertFalse(cancelled.get());
            assertTrue(await(Duration.ofSeconds(3), () -> runtime.activeCount() == 0));
        }
    }

    @Test
    void timeoutCancelsCooperativeHandlerAndReportsFailure() throws Exception {
        FakeDispatcher rpc = new FakeDispatcher();
        CountDownLatch began = new CountDownLatch(1);
        try (WorkerRuntime runtime = new WorkerRuntime(rpc, settings(1, 1), Map.of("safe.echo", (payload, ctx) -> {
            began.countDown();
            while (true) {
                ctx.checkCancelled();
                Thread.sleep(25);
            }
        }))) {
            runtime.start();
            assertTrue(began.await(5, TimeUnit.SECONDS));
            assertTrue(rpc.completed.await(7, TimeUnit.SECONDS));
            assertEquals("FAILED", rpc.outcome.get());
            assertTrue(await(Duration.ofSeconds(3), () -> runtime.activeCount() == 0));
        }
    }

    @Test
    void explicitCancellationInterruptsCooperativeHandler() throws Exception {
        FakeDispatcher rpc = new FakeDispatcher();
        CountDownLatch began = new CountDownLatch(1);
        try (WorkerRuntime runtime = new WorkerRuntime(rpc, settings(1, 10), Map.of("safe.echo", (payload, ctx) -> {
            began.countDown();
            while (true) { ctx.checkCancelled(); Thread.sleep(20); }
        }))) {
            runtime.start();
            assertTrue(began.await(5, TimeUnit.SECONDS));
            assertTrue(runtime.requestCancel(rpc.taskId));
            assertTrue(rpc.completed.await(5, TimeUnit.SECONDS));
            assertEquals("FAILED", rpc.outcome.get());
            assertFalse(runtime.requestCancel("missing-task-id"));
        }
    }

    @Test
    void staleHeartbeatPreventsCompletingOldEpoch() throws Exception {
        FakeDispatcher rpc = new FakeDispatcher();
        rpc.staleHeartbeat.set(true);
        CountDownLatch began = new CountDownLatch(1);
        try (WorkerRuntime runtime = new WorkerRuntime(rpc, settings(1, 5), Map.of("safe.echo", (p, ctx) -> {
            began.countDown();
            while (true) { ctx.checkCancelled(); Thread.sleep(30); }
        }))) {
            runtime.start();
            assertTrue(began.await(5, TimeUnit.SECONDS));
            assertTrue(await(Duration.ofSeconds(4), () -> runtime.activeCount() == 0));
            assertEquals(0, rpc.completionCount.get(), "Stale workers must not complete");
            assertTrue(rpc.heartbeats.get() >= 1);
        }
    }

    @Test
    void boundedCapacityDoesNotPollWhileHandlerOccupiesOnlySlot() throws Exception {
        FakeDispatcher rpc = new FakeDispatcher();
        CountDownLatch began = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (WorkerRuntime runtime = new WorkerRuntime(rpc, settings(1, 10), Map.of("safe.echo", (payload, ctx) -> {
            began.countDown();
            release.await(5, TimeUnit.SECONDS);
        }))) {
            runtime.start();
            assertTrue(began.await(5, TimeUnit.SECONDS));
            int claimed = rpc.claims.get();
            assertEquals(1, runtime.activeCount());
            runtime.pollOnce();
            assertEquals(claimed, rpc.claims.get(), "No second claim while the only slot is busy");
            release.countDown();
            assertTrue(rpc.completed.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void registrationAndSettingsRejectUnboundedOrUntrustedHandlers() {
        FakeDispatcher rpc = new FakeDispatcher();
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntime(rpc, settings(1, 5), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkerRuntime(rpc, settings(1, 5),
                Map.of("com.example.UserScript", (p, ctx) -> { })));
        assertThrows(IllegalArgumentException.class, () -> new WorkerSettings("x", 1,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new WorkerSettings(WORKER, 1024,
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofSeconds(5)));
    }

    private static WorkerSettings settings(int concurrent, int timeoutSeconds) {
        return new WorkerSettings(WORKER, concurrent, Duration.ofMillis(150), Duration.ofMillis(250),
                Duration.ofSeconds(timeoutSeconds), Duration.ofSeconds(2));
    }

    private static boolean await(Duration timeout, java.util.function.BooleanSupplier ready) throws InterruptedException {
        long until = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < until) {
            if (ready.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return ready.getAsBoolean();
    }

    private static final class FakeDispatcher implements DispatcherRpc {
        final String taskId = UUID.randomUUID().toString();
        final AtomicInteger claims = new AtomicInteger();
        final AtomicInteger heartbeats = new AtomicInteger();
        final AtomicInteger completionCount = new AtomicInteger();
        final AtomicReference<String> outcome = new AtomicReference<>();
        final AtomicBoolean staleHeartbeat = new AtomicBoolean();
        final CountDownLatch completed = new CountDownLatch(1);
        @Override public DispatchEnqueueReply enqueue(DispatchEnqueueCommand unused) { throw new UnsupportedOperationException(); }
        @Override public com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply cancelDagTask(
                com.peachsoft.otryx.flow.contracts.v1.DispatchCancelCommand unused) {
            throw new UnsupportedOperationException();
        }
        @Override public DispatchTaskListReply list(String owner, String project) { throw new UnsupportedOperationException(); }
        @Override public DispatchLogBatchReply appendLogs(DispatchLogBatchCommand command) {
            return new DispatchLogBatchReply("OK", command.entries().length, 0, 0);
        }
        @Override public DispatchLogPageView queryLogs(String owner, String project, String[] nodeRunIds,
                String cursor, int limit) {
            throw new UnsupportedOperationException();
        }
        @Override public DispatchLeaseReply claim(DispatchLeaseCommand request) {
            if (claims.getAndIncrement() != 0) return new DispatchLeaseReply(new DispatchLeaseView[0]);
            DispatchLeaseView claim = new DispatchLeaseView(taskId, UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), request.handlerKey(),
                    "{}", 30, 1, 3, 1, UUID.randomUUID().toString(), Instant.now().plusSeconds(30).toString());
            return new DispatchLeaseReply(new DispatchLeaseView[]{claim});
        }
        @Override public DispatchMutationReply heartbeat(DispatchHeartbeatCommand request) {
            heartbeats.incrementAndGet();
            return new DispatchMutationReply(staleHeartbeat.get() ? "STALE" : "OK", "LEASED");
        }
        @Override public DispatchMutationReply complete(DispatchCompleteCommand request) {
            outcome.set(request.outcome());
            completionCount.incrementAndGet();
            completed.countDown();
            return new DispatchMutationReply("OK", request.outcome());
        }
    }
}
