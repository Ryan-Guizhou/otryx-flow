package com.peachsoft.otryx.flow.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 验证 Engine Outbox RPC 不持有 DB 事务、幂等重投、回滚和 Dispatcher 中断恢复。 */
class EngineDispatchRelayTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String SCHEDULE = "00000000-0000-4000-8000-000000000002";
    private static final String JOB = "00000000-0000-4000-8000-000000000003";
    private JdbcTemplate jdbc;
    private EngineRpcService engine;
    private EngineDispatchRelay relay;
    private DispatcherRpc dispatcher;
    private JobRpc jobs;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:engine-relay-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_engine_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "payload_fingerprint CHAR(64), run_id VARCHAR(36) UNIQUE, received_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_workflow_run (id VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) UNIQUE, "
                + "owner_username VARCHAR(64), project_id VARCHAR(36), schedule_id VARCHAR(36), "
                + "schedule_revision INT, job_id VARCHAR(36), job_revision INT, planned_fire_at_utc TIMESTAMP(3), "
                + "origin VARCHAR(16), status VARCHAR(24), created_at TIMESTAMP(3), updated_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_engine_dispatch_outbox (run_id VARCHAR(36) PRIMARY KEY, "
                + "event_id VARCHAR(36) UNIQUE, delivery_state VARCHAR(16), claim_token VARCHAR(36), "
                + "lease_until TIMESTAMP(3), attempts INT, next_attempt_at TIMESTAMP(3) "
                + "DEFAULT TIMESTAMP '1970-01-01 00:00:00', last_error VARCHAR(120), "
                + "delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
        var manager = new DataSourceTransactionManager(source);
        engine = new EngineRpcService(jdbc, manager);
        relay = new EngineDispatchRelay(jdbc, manager);
        dispatcher = mock(DispatcherRpc.class);
        jobs = mock(JobRpc.class);
        ReflectionTestUtils.setField(relay, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(relay, "jobs", jobs);
        when(jobs.revision(eq("admin"), eq(PROJECT), eq(JOB), eq(2))).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return new JobRevisionView(JOB, 2, "Logical handler", "safe.echo", "{}", 30, 2, 2L,
                    "2026-10-26T08:00:00Z");
        });
    }

    @Test
    void createdEngineRunAndOutboxAreAtomicThenDispatchAckUpdatesRun() {
        String runId = engine.acceptScheduledFire(event()).runId();
        assertEquals("PENDING", state(runId));
        when(dispatcher.enqueue(any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            DispatchEnqueueCommand request = call.getArgument(0);
            assertEquals(runId, request.runId());
            assertEquals("safe.echo", request.handlerKey());
            return new DispatchEnqueueReply("CREATED", UUID.randomUUID().toString(), "READY");
        });
        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", state(runId));
        assertEquals("DISPATCHED", runState(runId));
        verify(dispatcher, times(1)).enqueue(any());
    }

    @Test
    void timeoutAfterReceiverCommitRetriesSameRunWithoutCreatingSecondOutbox() {
        String runId = engine.acceptScheduledFire(event()).runId();
        when(dispatcher.enqueue(any())).thenThrow(new IllegalStateException("RPC_TIMEOUT"))
                .thenReturn(new DispatchEnqueueReply("DUPLICATE", UUID.randomUUID().toString(), "LEASED"));
        assertEquals(0, relay.relayOnce());
        assertEquals("PENDING", state(runId));
        assertEquals("WAITING_DISPATCH", runState(runId));
        jdbc.update("UPDATE flow_engine_dispatch_outbox SET next_attempt_at = TIMESTAMP '2000-01-01 00:00:00'");
        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", state(runId));
        assertEquals("DISPATCHED", runState(runId));
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM flow_engine_dispatch_outbox", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_dispatch_outbox", Integer.class));
        verify(dispatcher, times(2)).enqueue(any());
    }

    @Test
    void missingImmutableJobRevisionQuarantinesWithoutPublishingFalseSuccess() {
        String runId = engine.acceptScheduledFire(event()).runId();
        when(jobs.revision(any(), any(), any(), eq(2))).thenReturn(null);
        assertEquals(0, relay.relayOnce());
        assertEquals("DEAD", state(runId));
        assertEquals("WAITING_DISPATCH", runState(runId));
        verifyNoInteractions(dispatcher);
    }

    private String state(String id) {
        return jdbc.queryForObject("SELECT delivery_state FROM flow_engine_dispatch_outbox "
                + "WHERE run_id = ?", String.class, id);
    }
    private String runState(String id) {
        return jdbc.queryForObject("SELECT status FROM flow_workflow_run WHERE id = ?", String.class, id);
    }
    private static ScheduleFireCommand event() {
        return new ScheduleFireCommand(UUID.randomUUID().toString(), "admin", PROJECT,
                SCHEDULE, 1, JOB, 2, "2026-10-26T08:00:00Z", "CRON");
    }
}
