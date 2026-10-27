package com.peachsoft.otryx.flow.dispatcher;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCompleteCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchHeartbeatCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogBatchCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogEntry;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogPageView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseView;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Dispatcher 队列幂等、并发抢占、代际隔离、租约续期、失败重试及耗尽回收测试。 */
class DispatcherRpcServiceTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String SCHEDULE = "00000000-0000-4000-8000-000000000002";
    private static final String JOB = "00000000-0000-4000-8000-000000000003";
    private DispatcherRpcService service;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;

    @BeforeEach
    void prepare() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:dispatch-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_dispatch_task (id VARCHAR(36) PRIMARY KEY, "
                + "engine_run_id VARCHAR(36) UNIQUE, event_id VARCHAR(36) UNIQUE, owner_username VARCHAR(64), "
                + "project_id VARCHAR(36), schedule_id VARCHAR(36), job_id VARCHAR(36), job_revision INT, "
                + "handler_key VARCHAR(128), parameters_json TEXT, timeout_seconds INT, max_attempts INT, "
                + "attempts INT, status VARCHAR(24), lease_epoch BIGINT, lease_token VARCHAR(36), "
                + "worker_id VARCHAR(64), lease_until TIMESTAMP(3), next_attempt_at TIMESTAMP(3), "
                + "payload_fingerprint CHAR(64), created_at TIMESTAMP(3), updated_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_dispatch_attempt (task_id VARCHAR(36), lease_epoch BIGINT, "
                + "worker_id VARCHAR(64), lease_token VARCHAR(36), status VARCHAR(24), "
                + "started_at TIMESTAMP(3), finished_at TIMESTAMP(3), PRIMARY KEY(task_id,lease_epoch))");
        jdbc.execute("CREATE TABLE flow_dispatch_log (id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36), "
                + "engine_run_id VARCHAR(36), owner_username VARCHAR(64), project_id VARCHAR(36), "
                + "lease_epoch BIGINT, sequence_no BIGINT, level VARCHAR(8), message VARCHAR(2048), "
                + "fields_json VARCHAR(4096), created_at TIMESTAMP(3), UNIQUE(task_id,lease_epoch,sequence_no), "
                + "FOREIGN KEY(task_id) REFERENCES flow_dispatch_task(id))");
        jdbc.execute("CREATE TABLE flow_dispatch_outcome_outbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "engine_run_id VARCHAR(36) UNIQUE, task_id VARCHAR(36), project_id VARCHAR(36), "
                + "lease_epoch BIGINT, outcome VARCHAR(16), delivery_state VARCHAR(16), "
                + "claim_token VARCHAR(36), lease_until TIMESTAMP(3), attempts INT, "
                + "next_attempt_at TIMESTAMP(3), last_error VARCHAR(120), "
                + "delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
        manager = new DataSourceTransactionManager(source);
        service = new DispatcherRpcService(jdbc, manager, new ObjectMapper());
    }

    @Test
    void duplicateDeliveryHasOneTaskAndConflictCannotOverwrite() {
        DispatchEnqueueCommand payload = event(2);
        var first = service.enqueue(payload);
        var second = service.enqueue(payload);
        assertEquals("CREATED", first.code());
        assertEquals("DUPLICATE", second.code());
        assertEquals(first.taskId(), second.taskId());
        DispatchEnqueueCommand conflict = new DispatchEnqueueCommand(payload.runId(), payload.eventId(),
                "admin", PROJECT, SCHEDULE, JOB, 1, "safe.other", "{}", 30, 2);
        assertEquals("CONFLICT", service.enqueue(conflict).code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_task", Integer.class));
        assertEquals(1, service.list("admin", PROJECT).tasks().length);
        assertEquals(0, service.list("intruder", PROJECT).tasks().length);
        assertEquals(0, service.list("admin", UUID.randomUUID().toString()).tasks().length);
    }

    @Test
    void expiredLeaseIsReclaimedAndStaleWorkerCanNeverComplete() {
        var enqueued = service.enqueue(event(2));
        DispatchLeaseView first = service.claim(new DispatchLeaseCommand("worker-alpha", "safe.echo", 1)).leases()[0];
        assertEquals(1, first.attempt());
        assertEquals(1L, first.leaseEpoch());
        assertEquals("OK", service.heartbeat(new DispatchHeartbeatCommand("worker-alpha", first.taskId(),
                first.leaseEpoch(), first.leaseToken())).code());
        assertEquals(0, service.claim(new DispatchLeaseCommand("worker-bravo", "safe.echo", 1)).leases().length);
        jdbc.update("UPDATE flow_dispatch_task SET lease_until = TIMESTAMP '2000-01-01 00:00:00' WHERE id = ?", first.taskId());
        DispatchLeaseView second = service.claim(new DispatchLeaseCommand("worker-bravo", "safe.echo", 1)).leases()[0];
        assertEquals(2L, second.leaseEpoch());
        assertEquals(2, second.attempt());
        assertNotEquals(first.leaseToken(), second.leaseToken());
        assertEquals("STALE", service.complete(new DispatchCompleteCommand("worker-alpha", first.taskId(),
                first.leaseEpoch(), first.leaseToken(), "SUCCEEDED")).code());
        assertEquals("STALE", service.heartbeat(new DispatchHeartbeatCommand("worker-alpha", first.taskId(),
                first.leaseEpoch(), first.leaseToken())).code());
        assertEquals("OK", service.complete(new DispatchCompleteCommand("worker-bravo", second.taskId(),
                second.leaseEpoch(), second.leaseToken(), "SUCCEEDED")).code());
        assertEquals("SUCCEEDED", service.list("admin", PROJECT).tasks()[0].status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
        assertEquals("STALE", service.complete(new DispatchCompleteCommand("worker-bravo", second.taskId(),
                second.leaseEpoch(), second.leaseToken(), "SUCCEEDED")).code());
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM flow_dispatch_attempt "
                + "WHERE task_id = ? AND lease_epoch = 1", String.class, enqueued.taskId()));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM flow_dispatch_attempt "
                + "WHERE task_id = ? AND lease_epoch = 2", String.class, enqueued.taskId()));
    }

    @Test
    void failedAttemptRetriesWithinBoundAndThenStops() {
        service.enqueue(event(2));
        var a = service.claim(new DispatchLeaseCommand("worker-alpha", "safe.echo", 1)).leases()[0];
        assertEquals("READY", service.complete(new DispatchCompleteCommand("worker-alpha", a.taskId(),
                a.leaseEpoch(), a.leaseToken(), "FAILED")).taskStatus());
        assertEquals(0, service.claim(new DispatchLeaseCommand("worker-bravo", "safe.echo", 1)).leases().length);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
        jdbc.update("UPDATE flow_dispatch_task SET next_attempt_at = TIMESTAMP '2000-01-01 00:00:00'");
        var b = service.claim(new DispatchLeaseCommand("worker-bravo", "safe.echo", 1)).leases()[0];
        assertEquals(2, b.attempt());
        assertEquals("FAILED", service.complete(new DispatchCompleteCommand("worker-bravo", b.taskId(),
                b.leaseEpoch(), b.leaseToken(), "FAILED")).taskStatus());
        assertEquals(0, service.claim(new DispatchLeaseCommand("worker-alpha", "safe.echo", 1)).leases().length);
        assertEquals("FAILED", service.list("admin", PROJECT).tasks()[0].status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
    }

    @Test
    void exhaustedExpiredLeaseIsTerminalAndCannotBeResurrected() {
        service.enqueue(event(1));
        var claim = service.claim(new DispatchLeaseCommand("worker-one", "safe.echo", 1)).leases()[0];
        jdbc.update("UPDATE flow_dispatch_task SET lease_until = TIMESTAMP '2000-01-01 00:00:00'");
        assertEquals(1, service.expireExhaustedOnce());
        assertEquals("FAILED", service.list("admin", PROJECT).tasks()[0].status());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
        assertEquals(0, service.claim(new DispatchLeaseCommand("worker-next", "safe.echo", 1)).leases().length);
        assertEquals("STALE", service.complete(new DispatchCompleteCommand("worker-one", claim.taskId(),
                claim.leaseEpoch(), claim.leaseToken(), "SUCCEEDED")).code());
    }

    @Test
    void concurrentWorkersAcrossDispatcherInstancesCannotClaimSameTask() throws Exception {
        service.enqueue(event(2));
        var replica = new DispatcherRpcService(jdbc, manager, new ObjectMapper());
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var a = threads.submit(() -> { start.await(); return service.claim(
                    new DispatchLeaseCommand("worker-alpha", "safe.echo", 1)).leases().length; });
            var b = threads.submit(() -> { start.await(); return replica.claim(
                    new DispatchLeaseCommand("worker-bravo", "safe.echo", 1)).leases().length; });
            start.countDown();
            assertEquals(1, a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_attempt", Integer.class));
    }

    @Test
    void rejectsOversizedParametersInvalidIdsAndUnboundedBatch() {
        DispatchEnqueueCommand c = event(2);
        assertThrows(IllegalArgumentException.class, () -> service.enqueue(new DispatchEnqueueCommand(
                c.runId(), c.eventId(), "admin", PROJECT, SCHEDULE, JOB, 1, "safe.echo", "x".repeat(16385), 30, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> service.claim(new DispatchLeaseCommand("worker-a", "safe.echo", 9)));
        assertThrows(IllegalArgumentException.class, () -> service.heartbeat(
                new DispatchHeartbeatCommand("bad", "invalid-id", 0, "invalid-id")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_task", Integer.class));
    }


    @Test
    void e2eTargetClaimSelectsOnlyRequestedNodeEvenWithOlderReadyTasks() {
        var older = event(2);
        var requested = event(2);
        service.enqueue(older);
        service.enqueue(requested);
        var lease = service.claimForE2eTarget(
                new DispatchLeaseCommand("probe-dag","safe.echo",1), requested.runId()).leases();
        assertEquals(1, lease.length);
        assertEquals(requested.runId(), lease[0].runId());
        assertEquals("READY", jdbc.queryForObject(
                "SELECT status FROM flow_dispatch_task WHERE engine_run_id=?",
                String.class, older.runId()));
        assertEquals("LEASED", jdbc.queryForObject(
                "SELECT status FROM flow_dispatch_task WHERE engine_run_id=?",
                String.class, requested.runId()));
    }

    @Test
    void cancellationBeforeEnqueueCreatesDurableTombstoneAndNeverLeases() {
        var command = event(2);
        var cancel = new DispatchCancelCommand(command.runId(), command.ownerUsername(),
                command.projectId(), command.scheduleId(), command.jobId(), command.jobRevision());
        assertEquals("CANCELLED", service.cancelDagTask(cancel).code());
        assertEquals("CANCELLED", service.cancelDagTask(cancel).code());
        assertEquals("CANCELLED", service.enqueue(command).code());
        assertEquals(0, service.claim(new DispatchLeaseCommand("worker-zero","safe.echo",8)).leases().length);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_task", Integer.class));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM flow_dispatch_task WHERE engine_run_id=?", String.class, command.runId()));
        assertEquals("CONFLICT", service.cancelDagTask(new DispatchCancelCommand(command.runId(),
                "other-owner", PROJECT, SCHEDULE, JOB, 1)).code());
    }

    @Test
    void leasedWorkerLosesEpochImmediatelyAfterCancellation() {
        var command = event(2);
        service.enqueue(command);
        DispatchLeaseView lease = service.claim(new DispatchLeaseCommand(
                "worker-one","safe.echo",1)).leases()[0];
        var cancel = new DispatchCancelCommand(command.runId(), "admin", PROJECT, SCHEDULE, JOB, 1);
        assertEquals("CANCELLED", service.cancelDagTask(cancel).taskStatus());
        assertEquals("STALE", service.heartbeat(new DispatchHeartbeatCommand(
                "worker-one", lease.taskId(), lease.leaseEpoch(), lease.leaseToken())).code());
        assertEquals("STALE", service.complete(new DispatchCompleteCommand(
                "worker-one", lease.taskId(), lease.leaseEpoch(), lease.leaseToken(), "SUCCEEDED")).code());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM flow_dispatch_attempt WHERE task_id=? AND lease_epoch=?",
                String.class, lease.taskId(), lease.leaseEpoch()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
    }

    @Test
    void terminalWorkerOutcomeAlwaysPrecedesLateCancellation() {
        var command = event(1);
        service.enqueue(command);
        var lease = service.claim(new DispatchLeaseCommand("worker-done","safe.echo",1)).leases()[0];
        assertEquals("SUCCEEDED", service.complete(new DispatchCompleteCommand(
                "worker-done",lease.taskId(),lease.leaseEpoch(),lease.leaseToken(),"SUCCEEDED")).taskStatus());
        var cancel = new DispatchCancelCommand(command.runId(), "admin", PROJECT, SCHEDULE, JOB, 1);
        assertEquals("TERMINAL", service.cancelDagTask(cancel).code());
        assertEquals("SUCCEEDED", service.cancelDagTask(cancel).taskStatus());
        assertEquals("SUCCEEDED", jdbc.queryForObject(
                "SELECT status FROM flow_dispatch_task WHERE engine_run_id=?", String.class, command.runId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
    }

    @Test
    void enqueueCancelConcurrentRaceNeverLeavesClaimableTask() throws Exception {
        for (int i = 0; i < 6; i++) {
            var command = event(2);
            var cancel = new DispatchCancelCommand(command.runId(), "admin", PROJECT, SCHEDULE, JOB, 1);
            CountDownLatch start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var enqueue = pool.submit(() -> { start.await(); return service.enqueue(command); });
                var cancelled = pool.submit(() -> { start.await(); return service.cancelDagTask(cancel); });
                start.countDown();
                enqueue.get(10, TimeUnit.SECONDS);
                assertEquals("CANCELLED", cancelled.get(10, TimeUnit.SECONDS).code());
            }
            assertEquals("CANCELLED", jdbc.queryForObject(
                    "SELECT status FROM flow_dispatch_task WHERE engine_run_id=?",
                    String.class, command.runId()));
        }
        assertEquals(0, service.claim(new DispatchLeaseCommand("worker-racer","safe.echo",8)).leases().length);
    }

    @Test
    void logsAreLeaseBoundedRedactedIdempotentAndCursorPaginated() {
        DispatchEnqueueCommand command = event(2);
        service.enqueue(command);
        DispatchLeaseView lease = service.claim(new DispatchLeaseCommand("worker-logs", "safe.echo", 1)).leases()[0];
        java.util.Map<String, String> largeFields = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 16; index++) {
            largeFields.put("field" + index + "x".repeat(55), "\\".repeat(256));
        }
        var entries = new DispatchLogEntry[] {
                new DispatchLogEntry(1, "INFO", "password=top-secret Bearer abc.def",
                        java.util.Map.of("accessToken", "top-secret", "region", "CN")),
                new DispatchLogEntry(2, "WARN", "retry scheduled",
                        java.util.Map.of("credential", "another-secret")),
                new DispatchLogEntry(3, "INFO", "bounded fields", largeFields)
        };
        var batch = new DispatchLogBatchCommand("worker-logs", lease.taskId(),
                lease.leaseEpoch(), lease.leaseToken(), entries);
        var first = service.appendLogs(batch);
        assertEquals("OK", first.code());
        assertEquals(3, first.accepted());
        var replay = service.appendLogs(batch);
        assertEquals(3, replay.duplicates());
        assertEquals(0, replay.accepted());

        DispatchLogPageView page1 = service.queryLogs("admin", PROJECT,
                new String[]{command.runId()}, null, 1);
        assertEquals(1, page1.items().length);
        assertNotNull(page1.nextCursor());
        DispatchLogPageView page2 = service.queryLogs("admin", PROJECT,
                new String[]{command.runId()}, page1.nextCursor(), 1);
        assertEquals(1, page2.items().length);
        assertNotNull(page2.nextCursor());
        DispatchLogPageView page3 = service.queryLogs("admin", PROJECT,
                new String[]{command.runId()}, page2.nextCursor(), 1);
        assertEquals(1, page3.items().length);
        assertNull(page3.nextCursor());
        String all = page1.items()[0].message() + page1.items()[0].fieldsJson()
                + page2.items()[0].message() + page2.items()[0].fieldsJson()
                + page3.items()[0].message() + page3.items()[0].fieldsJson();
        assertTrue(java.util.stream.Stream.of(page1.items()[0], page2.items()[0], page3.items()[0])
                .allMatch(item -> item.fieldsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4096));
        assertFalse(all.contains("top-secret"));
        assertFalse(all.contains("another-secret"));
        assertTrue(all.contains("[REDACTED]"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_log", Integer.class));

        jdbc.update("UPDATE flow_dispatch_task SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",
                lease.taskId());
        DispatchLeaseView next = service.claim(new DispatchLeaseCommand("worker-next", "safe.echo", 1)).leases()[0];
        assertEquals(2L, next.leaseEpoch());
        assertEquals("STALE", service.appendLogs(batch).code());
    }

    private static DispatchEnqueueCommand event(int max) {
        return new DispatchEnqueueCommand(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "admin", PROJECT, SCHEDULE, JOB, 1, "safe.echo", "{}", 30, max);
    }
}
