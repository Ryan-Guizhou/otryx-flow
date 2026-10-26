package com.peachsoft.otryx.flow.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Engine Outcome Inbox 的完整载荷去重、终态不可倒退和冲突拒绝测试。 */
class EngineOutcomeTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String SCHEDULE = "00000000-0000-4000-8000-000000000002";
    private static final String JOB = "00000000-0000-4000-8000-000000000003";
    private JdbcTemplate jdbc;
    private EngineRpcService service;

    @BeforeEach
    void init() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:engine-outcome-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE flow_engine_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "payload_fingerprint CHAR(64), run_id VARCHAR(36) UNIQUE, received_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_workflow_run (id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) UNIQUE, "
                + "owner_username VARCHAR(64), project_id VARCHAR(36), schedule_id VARCHAR(36), "
                + "schedule_revision INT, job_id VARCHAR(36), job_revision INT, planned_fire_at_utc TIMESTAMP(3), "
                + "origin VARCHAR(16), status VARCHAR(24), created_at TIMESTAMP(3), updated_at TIMESTAMP(3), "
                + "UNIQUE(schedule_id, schedule_revision, planned_fire_at_utc, origin))");
        jdbc.execute("CREATE TABLE flow_engine_dispatch_outbox (run_id VARCHAR(36) PRIMARY KEY, "
                + "event_id VARCHAR(36) UNIQUE, delivery_state VARCHAR(16), claim_token VARCHAR(36), "
                + "lease_until TIMESTAMP(3), attempts INT, next_attempt_at TIMESTAMP(3), "
                + "last_error VARCHAR(120), delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_engine_outcome_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "engine_run_id VARCHAR(36) UNIQUE, task_id VARCHAR(36), project_id VARCHAR(36), "
                + "lease_epoch BIGINT, outcome VARCHAR(16), payload_fingerprint CHAR(64), accepted_at TIMESTAMP(3))");
        service = new EngineRpcService(jdbc, new DataSourceTransactionManager(ds));
    }

    @Test
    void outcomeIsAtomicIdempotentAndCannotBeRewritten() {
        var fire = new ScheduleFireCommand(UUID.randomUUID().toString(), "admin", PROJECT, SCHEDULE,
                1, JOB, 2, "2026-10-26T09:00:00Z", "CRON");
        var run = service.acceptScheduledFire(fire);
        var result = new EngineOutcomeCommand(UUID.randomUUID().toString(), run.runId(),
                UUID.randomUUID().toString(), PROJECT, 2L, "SUCCEEDED");
        assertEquals("ACCEPTED", service.acknowledgeTaskOutcome(result).code());
        assertEquals("DUPLICATE", service.acknowledgeTaskOutcome(result).code());
        assertEquals("SUCCEEDED", service.listRuns("admin", PROJECT).runs()[0].status());
        assertEquals("CONFLICT", service.acknowledgeTaskOutcome(new EngineOutcomeCommand(
                result.eventId(), result.runId(), result.taskId(), PROJECT, 2L, "FAILED")).code());
        assertEquals("CONFLICT", service.acknowledgeTaskOutcome(new EngineOutcomeCommand(
                UUID.randomUUID().toString(), result.runId(), result.taskId(), PROJECT, 2L, "SUCCEEDED")).code());
        assertEquals("CONFLICT", service.acknowledgeTaskOutcome(new EngineOutcomeCommand(
                UUID.randomUUID().toString(), result.runId(), result.taskId(), UUID.randomUUID().toString(),
                2L, "SUCCEEDED")).code());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_outcome_inbox", Integer.class));
        assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM flow_workflow_run", String.class));
    }

    @Test
    void cannotCreateOutcomeForMissingRunOrInvalidEpoch() {
        var outcome = new EngineOutcomeCommand(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), PROJECT, 1L, "SUCCEEDED");
        assertEquals("CONFLICT", service.acknowledgeTaskOutcome(outcome).code());
        assertThrows(IllegalArgumentException.class, () -> service.acknowledgeTaskOutcome(
                new EngineOutcomeCommand(outcome.eventId(), outcome.runId(), outcome.taskId(), PROJECT,
                        0L, "SUCCEEDED")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_outcome_inbox", Integer.class));
    }
}
