package com.peachsoft.otryx.flow.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 验证重投、并发 Inbox 去重、冲突拒绝、租户隔离和事务回滚。 */
class EngineRpcServiceTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String SCHEDULE = "00000000-0000-4000-8000-000000000002";
    private static final String JOB = "00000000-0000-4000-8000-000000000003";
    private EngineRpcService engine;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:engine-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE flow_engine_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "payload_fingerprint CHAR(64), run_id VARCHAR(36) UNIQUE, received_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_workflow_run (id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) UNIQUE, "
                + "owner_username VARCHAR(64), project_id VARCHAR(36), schedule_id VARCHAR(36), "
                + "schedule_revision INT, job_id VARCHAR(36), job_revision INT, planned_fire_at_utc TIMESTAMP(3), "
                + "origin VARCHAR(16), status VARCHAR(24), control_state VARCHAR(24) DEFAULT 'NONE', "
                + "run_version BIGINT DEFAULT 1, failure_code VARCHAR(64), finished_at TIMESTAMP(3), "
                + "created_at TIMESTAMP(3), updated_at TIMESTAMP(3), "
                + "UNIQUE(schedule_id, schedule_revision, planned_fire_at_utc, origin))");
        jdbc.execute("CREATE TABLE flow_engine_outcome_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "engine_run_id VARCHAR(36) UNIQUE, task_id VARCHAR(36), project_id VARCHAR(36), "
                + "lease_epoch BIGINT, outcome VARCHAR(16), payload_fingerprint CHAR(64), accepted_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_engine_dispatch_outbox (run_id VARCHAR(36) PRIMARY KEY, "
                + "event_id VARCHAR(36) UNIQUE, delivery_state VARCHAR(16), claim_token VARCHAR(36), "
                + "lease_until TIMESTAMP(3), attempts INT, next_attempt_at TIMESTAMP(3), "
                + "last_error VARCHAR(120), delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
        engine = new EngineRpcService(jdbc, new DataSourceTransactionManager(ds));
    }

    @Test
    void replaySameEventAndRejectMutatedContent() {
        var first = event(UUID.randomUUID().toString(), "2026-10-26T08:00:00Z");
        var created = engine.acceptScheduledFire(first);
        var replay = engine.acceptScheduledFire(first);
        assertEquals("CREATED", created.code());
        assertEquals("DUPLICATE", replay.code());
        assertEquals(created.runId(), replay.runId());
        assertEquals("WAITING_DISPATCH", replay.runStatus());
        assertEquals("CONFLICT", engine.acceptScheduledFire(new ScheduleFireCommand(first.eventId(),
                "admin", PROJECT, SCHEDULE, 1, JOB, 9, first.plannedFireUtc(), "CRON")).code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_workflow_run", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_dispatch_outbox", Integer.class));
    }

    @Test
    void differentEventIdCannotClaimSameLogicalFireAndOwnerIsScoped() {
        assertEquals("CREATED", engine.acceptScheduledFire(event(UUID.randomUUID().toString(),
                "2026-10-26T08:00:00Z")).code());
        assertEquals("CONFLICT", engine.acceptScheduledFire(event(UUID.randomUUID().toString(),
                "2026-10-26T08:00:00Z")).code());
        assertEquals(1, engine.listRuns("admin", PROJECT).runs().length);
        assertEquals(0, engine.listRuns("other", PROJECT).runs().length);
        assertEquals(0, engine.listRuns("admin", UUID.randomUUID().toString()).runs().length);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
    }

    @Test
    void concurrentRetryCreatesExactlyOneRun() throws Exception {
        var event = event(UUID.randomUUID().toString(), "2026-10-26T08:05:00Z");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return engine.acceptScheduledFire(event); });
            var second = pool.submit(() -> { start.await(); return engine.acceptScheduledFire(event); });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertTrue(("CREATED".equals(a.code()) && "DUPLICATE".equals(b.code()))
                    || ("DUPLICATE".equals(a.code()) && "CREATED".equals(b.code())));
            assertEquals(a.runId(), b.runId());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_workflow_run", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_dispatch_outbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
    }

    @Test
    void inboxRollsBackWhenRunInsertFails() {
        jdbc.execute("DROP TABLE flow_workflow_run");
        assertThrows(RuntimeException.class, () -> engine.acceptScheduledFire(
                event(UUID.randomUUID().toString(), "2026-10-26T08:10:00Z")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_dispatch_outbox", Integer.class));
    }

    @Test
    void cancelledWorkerOutcomeCompletesCancelRequestedRun() {
        ScheduleFireCommand event = event(UUID.randomUUID().toString(), "2026-10-26T08:15:00Z");
        String runId = engine.acceptScheduledFire(event).runId();
        jdbc.update("UPDATE flow_workflow_run SET status='CANCEL_REQUESTED', control_state='CANCEL_REQUESTED' "
                + "WHERE id=?", runId);
        EngineOutcomeCommand outcome = new EngineOutcomeCommand(event.eventId(), runId,
                UUID.randomUUID().toString(), PROJECT, 3, "CANCELLED");
        var accepted = engine.acknowledgeTaskOutcome(outcome);
        var duplicate = engine.acknowledgeTaskOutcome(outcome);
        assertEquals("ACCEPTED", accepted.code());
        assertEquals("DUPLICATE", duplicate.code());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM flow_workflow_run WHERE id=?", String.class, runId));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT control_state FROM flow_workflow_run WHERE id=?", String.class, runId));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT run_version FROM flow_workflow_run WHERE id=?", Long.class, runId));
        org.junit.jupiter.api.Assertions.assertNotNull(jdbc.queryForObject(
                "SELECT finished_at FROM flow_workflow_run WHERE id=?", java.sql.Timestamp.class, runId));
    }

    @Test
    void invalidSourceNeverWritesInbox() {
        var invalid = new ScheduleFireCommand(UUID.randomUUID().toString(), "admin", PROJECT,
                SCHEDULE, 0, JOB, 2, "2026-10-26T08:00:00Z", "CRON");
        assertThrows(IllegalArgumentException.class, () -> engine.acceptScheduledFire(invalid));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_dispatch_outbox", Integer.class));
    }

    private static ScheduleFireCommand event(String id, String instant) {
        return new ScheduleFireCommand(id, "admin", PROJECT, SCHEDULE, 1, JOB, 2, instant, "CRON");
    }
}
