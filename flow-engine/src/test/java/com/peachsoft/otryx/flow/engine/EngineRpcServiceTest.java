package com.peachsoft.otryx.flow.engine;

import static org.junit.jupiter.api.Assertions.*;

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
                + "origin VARCHAR(16), status VARCHAR(24), created_at TIMESTAMP(3), updated_at TIMESTAMP(3), "
                + "UNIQUE(schedule_id, schedule_revision, planned_fire_at_utc, origin))");
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
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
    }

    @Test
    void inboxRollsBackWhenRunInsertFails() {
        jdbc.execute("DROP TABLE flow_workflow_run");
        assertThrows(RuntimeException.class, () -> engine.acceptScheduledFire(
                event(UUID.randomUUID().toString(), "2026-10-26T08:10:00Z")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
    }

    @Test
    void invalidSourceNeverWritesInbox() {
        var invalid = new ScheduleFireCommand(UUID.randomUUID().toString(), "admin", PROJECT,
                SCHEDULE, 0, JOB, 2, "2026-10-26T08:00:00Z", "CRON");
        assertThrows(IllegalArgumentException.class, () -> engine.acceptScheduledFire(invalid));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_inbox", Integer.class));
    }

    private static ScheduleFireCommand event(String id, String instant) {
        return new ScheduleFireCommand(id, "admin", PROJECT, SCHEDULE, 1, JOB, 2, instant, "CRON");
    }
}
