package com.peachsoft.otryx.flow.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 验证普通 Job 取消 Outbox 的租约恢复及取消状态收敛。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/5 14:00
 */
class JobRunCancelRelayTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String RUN = "00000000-0000-4000-8000-000000000002";

    private JdbcTemplate jdbc;
    private JobRunCancelRelay relay;
    private DispatcherRpc dispatcher;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:cancel-relay-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_workflow_run (id VARCHAR(36) PRIMARY KEY, "
                + "status VARCHAR(24), control_state VARCHAR(24), run_version BIGINT DEFAULT 1, "
                + "finished_at TIMESTAMP(3), updated_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_engine_run_cancel_outbox (run_id VARCHAR(36) PRIMARY KEY, "
                + "event_id VARCHAR(36), owner_username VARCHAR(64), project_id VARCHAR(36), "
                + "delivery_state VARCHAR(16), claim_token VARCHAR(36), lease_until TIMESTAMP(3), "
                + "attempts INT, next_attempt_at TIMESTAMP(3), last_error VARCHAR(120), "
                + "created_at TIMESTAMP(3))");
        relay = new JobRunCancelRelay(jdbc, new DataSourceTransactionManager(source));
        dispatcher = mock(DispatcherRpc.class);
        ReflectionTestUtils.setField(relay, "dispatcher", dispatcher);
    }

    @Test
    void expiredInFlightClaimIsRecoveredWithoutPrematurelyCompletingRun() {
        insertRun();
        insertOutbox("IN_FLIGHT", Timestamp.from(Instant.now().minusSeconds(10)));
        when(dispatcher.cancelTask(any())).thenReturn(
                new DispatchCancelReply("CANCEL_REQUESTED", "CANCEL_REQUESTED"));

        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", outboxState());
        assertEquals("CANCEL_REQUESTED", runStatus());
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM flow_engine_run_cancel_outbox WHERE run_id=?", Integer.class, RUN));
    }

    @Test
    void immediateCancellationMarksRunTerminalOnlyAfterDispatcherConfirmsCancelled() {
        insertRun();
        insertOutbox("PENDING", Timestamp.from(Instant.now().minusSeconds(1)));
        when(dispatcher.cancelTask(any())).thenReturn(new DispatchCancelReply("CANCELLED", "CANCELLED"));

        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", outboxState());
        assertEquals("CANCELLED", runStatus());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT control_state FROM flow_workflow_run WHERE id=?", String.class, RUN));
        org.junit.jupiter.api.Assertions.assertNotNull(jdbc.queryForObject(
                "SELECT finished_at FROM flow_workflow_run WHERE id=?", Timestamp.class, RUN));
    }

    @Test
    void liveInFlightLeaseCannotBeClaimedByAnotherRelay() {
        insertRun();
        insertOutbox("IN_FLIGHT", Timestamp.from(Instant.now().plusSeconds(60)));

        assertEquals(0, relay.relayOnce());
        verifyNoInteractions(dispatcher);
        assertEquals("IN_FLIGHT", outboxState());
    }

    @Test
    void rpcFailureSchedulesRetryUsingDatabaseClock() {
        insertRun();
        insertOutbox("PENDING", Timestamp.from(Instant.now().minusSeconds(1)));
        AtomicInteger calls = new AtomicInteger();
        when(dispatcher.cancelTask(any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) throw new IllegalStateException("dispatcher unavailable");
            return new DispatchCancelReply("CANCEL_REQUESTED", "CANCEL_REQUESTED");
        });

        assertEquals(0, relay.relayOnce());
        assertEquals("PENDING", outboxState());
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM flow_engine_run_cancel_outbox WHERE run_id=?", Integer.class, RUN));
        assertTrue(jdbc.queryForObject("SELECT next_attempt_at > CURRENT_TIMESTAMP(3) "
                + "FROM flow_engine_run_cancel_outbox WHERE run_id=?", Boolean.class, RUN));

        jdbc.update("UPDATE flow_engine_run_cancel_outbox SET next_attempt_at=TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE run_id=?", RUN);
        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", outboxState());
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempts FROM flow_engine_run_cancel_outbox WHERE run_id=?", Integer.class, RUN));
    }

    @Test
    void expiredClaimCannotAcknowledgeAfterAnotherRelayTakesOwnership() throws Exception {
        insertRun();
        insertOutbox("PENDING", Timestamp.from(Instant.now().minusSeconds(1)));
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(dispatcher.cancelTask(any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertTrue(releaseFirst.await(5, TimeUnit.SECONDS));
                return new DispatchCancelReply("CANCELLED", "CANCELLED");
            }
            return new DispatchCancelReply("CANCEL_REQUESTED", "CANCEL_REQUESTED");
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(relay::relayOnce);
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            jdbc.update("UPDATE flow_engine_run_cancel_outbox SET lease_until=TIMESTAMP '2000-01-01 00:00:00' "
                    + "WHERE run_id=?", RUN);

            assertEquals(1, relay.relayOnce());
            assertEquals("CANCEL_REQUESTED", runStatus());
            releaseFirst.countDown();
            assertEquals(0, first.get(5, TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED", runStatus());
            assertEquals("DELIVERED", outboxState());
            assertEquals(2, jdbc.queryForObject(
                    "SELECT attempts FROM flow_engine_run_cancel_outbox WHERE run_id=?", Integer.class, RUN));
        } finally {
            releaseFirst.countDown();
        }
    }

    private void insertRun() {
        jdbc.update("INSERT INTO flow_workflow_run (id,status,control_state,run_version,updated_at) "
                        + "VALUES (?,'CANCEL_REQUESTED','CANCEL_REQUESTED',1,CURRENT_TIMESTAMP(3))",
                RUN);
    }

    private void insertOutbox(String state, Timestamp leaseUntil) {
        jdbc.update("INSERT INTO flow_engine_run_cancel_outbox "
                        + "(run_id,event_id,owner_username,project_id,delivery_state,claim_token,lease_until,"
                        + "attempts,next_attempt_at,created_at) VALUES (?,?,?,?,?,?,?,0,?,CURRENT_TIMESTAMP(3))",
                RUN, UUID.randomUUID().toString(), "admin", PROJECT, state,
                "IN_FLIGHT".equals(state) ? UUID.randomUUID().toString() : null,
                leaseUntil, Timestamp.from(Instant.now().minusSeconds(1)));
    }

    private String outboxState() {
        return jdbc.queryForObject("SELECT delivery_state FROM flow_engine_run_cancel_outbox WHERE run_id=?",
                String.class, RUN);
    }

    private String runStatus() {
        return jdbc.queryForObject("SELECT status FROM flow_workflow_run WHERE id=?", String.class, RUN);
    }
}
