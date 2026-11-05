package com.peachsoft.otryx.flow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.peachsoft.otryx.flow.contracts.v1.CancelJobRunCommand;
import com.peachsoft.otryx.flow.contracts.v1.StartManualJobRunCommand;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * 手动 Run 的幂等和取消不得覆盖终态。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
class JobRunRpcServiceTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String JOB = "00000000-0000-4000-8000-000000000002";
    private JobRunRpcService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:job-run-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_engine_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "payload_fingerprint CHAR(64), run_id VARCHAR(36), received_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_workflow_run (id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36), "
                + "owner_username VARCHAR(64), project_id VARCHAR(36), schedule_id VARCHAR(36), "
                + "schedule_revision INT, job_id VARCHAR(36), job_revision INT, planned_fire_at_utc TIMESTAMP(3), "
                + "origin VARCHAR(16), status VARCHAR(24), control_state VARCHAR(24), run_version BIGINT, "
                + "failure_code VARCHAR(64), retry_of_run_id VARCHAR(36), parameter_override TEXT, "
                + "started_at TIMESTAMP(3), finished_at TIMESTAMP(3), created_at TIMESTAMP(3), "
                + "updated_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_engine_dispatch_outbox (run_id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36), "
                + "delivery_state VARCHAR(16), attempts INT, created_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_job_run_request (owner_username VARCHAR(64), request_key VARCHAR(80), "
                + "fingerprint CHAR(64), run_id VARCHAR(36), created_at TIMESTAMP(3), "
                + "PRIMARY KEY(owner_username, request_key))");
        jdbc.execute("CREATE TABLE flow_engine_run_cancel_outbox (run_id VARCHAR(36) PRIMARY KEY, "
                + "event_id VARCHAR(36), owner_username VARCHAR(64), project_id VARCHAR(36), "
                + "delivery_state VARCHAR(16), claim_token VARCHAR(36), lease_until TIMESTAMP(3), attempts INT, "
                + "next_attempt_at TIMESTAMP(3), last_error VARCHAR(120), created_at TIMESTAMP(3))");
        service = new JobRunRpcService(jdbc, new DataSourceTransactionManager(source));
    }

    @Test
    void sameRequestReplaysAndDifferentBodyConflicts() {
        var created = service.startManual(command("request-key-012345", null));
        var replay = service.startManual(command("request-key-012345", null));
        var conflict = service.startManual(command("request-key-012345", "{\"values\":[1]}"));
        assertEquals("CREATED", created.code());
        assertEquals("DUPLICATE", replay.code());
        assertEquals(created.runId(), replay.runId());
        assertEquals("CONFLICT", conflict.code());
        assertEquals("MANUAL", service.detail("admin", PROJECT, created.runId()).origin());
    }

    @Test
    void cancelIsIdempotentAndRetryKeepsTheOldRun() {
        var created = service.startManual(new StartManualJobRunCommand("admin", PROJECT, JOB, 1,
                "request-key-abcdef12", "{\"value\":7}", null));
        var cancel = service.cancel(new CancelJobRunCommand("admin", PROJECT, created.runId(),
                "cancel-key-01234567", 1));
        var again = service.cancel(new CancelJobRunCommand("admin", PROJECT, created.runId(),
                "cancel-key-76543210", 0));
        var premature = service.startManual(new StartManualJobRunCommand("admin", PROJECT, JOB, 1,
                "retry-key-premature1", null, created.runId()));
        jdbc.update("UPDATE flow_workflow_run SET status='CANCELLED', control_state='CANCELLED' WHERE id=?",
                created.runId());
        var retried = service.startManual(new StartManualJobRunCommand("admin", PROJECT, JOB, 1,
                "retry-key-012345678", null, created.runId()));
        assertEquals("CANCEL_REQUESTED", cancel.code());
        assertEquals("DUPLICATE", again.code());
        assertEquals("CONFLICT", premature.code());
        assertEquals("RETRY", service.detail("admin", PROJECT, retried.runId()).origin());
        assertNotEquals(created.runId(), retried.runId());
        assertEquals("{\"value\":7}", jdbc.queryForObject(
                "SELECT parameter_override FROM flow_workflow_run WHERE id=?", String.class, retried.runId()));
        assertEquals("CANCELLED", service.detail("admin", PROJECT, created.runId()).status());
    }

    @Test
    void parameterOverridesMustBeBoundedJsonObjects() {
        assertEquals("CREATED", service.startManual(command("valid-object-0123456",
                "{\"nested\":{\"x\":1}}")).code());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.startManual(command("invalid-array-0123456", "[1,2]")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.startManual(command("invalid-json-0123456", "{not-json}")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.startManual(command("trailing-json-0123456", "{\"valid\":true} {\"extra\":true}")));
    }

    private static StartManualJobRunCommand command(String key, String override) {
        return new StartManualJobRunCommand("admin", PROJECT, JOB, 1, key, override, null);
    }
}
