package com.peachsoft.otryx.flow.dispatcher;

import static org.junit.jupiter.api.Assertions.*;

import com.peachsoft.otryx.flow.contracts.v1.EngineFireReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineRpc;
import com.peachsoft.otryx.flow.contracts.v1.EngineRunListReply;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** 独立 Dispatcher Outcome Relay 的有界 ACK 丢失恢复、租约回收及冲突隔离。 */
class DispatchOutcomeRelayTest {
    private JdbcTemplate jdbc;
    private DispatchOutcomeRelay relay;
    private FakeEngineRpc engine;
    private String eventId;

    @BeforeEach
    void init() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dispatch-outcome-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE flow_dispatch_outcome_outbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "engine_run_id VARCHAR(36) UNIQUE, task_id VARCHAR(36), project_id VARCHAR(36), "
                + "lease_epoch BIGINT, outcome VARCHAR(16), delivery_state VARCHAR(16), claim_token VARCHAR(36), "
                + "lease_until TIMESTAMP(3), attempts INT, next_attempt_at TIMESTAMP(3), "
                + "last_error VARCHAR(120), delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
        eventId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO flow_dispatch_outcome_outbox "
                        + "(event_id,engine_run_id,task_id,project_id,lease_epoch,outcome,delivery_state,"
                        + "attempts,next_attempt_at,created_at) "
                        + "VALUES (?,?,?,?,?,'SUCCEEDED','PENDING',0,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3))",
                eventId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2);
        engine = new FakeEngineRpc();
        relay = new DispatchOutcomeRelay(jdbc, new DataSourceTransactionManager(ds));
        ReflectionTestUtils.setField(relay, "engine", engine);
    }

    @Test
    void afterLostReplySameEventIsRetriedWithNoDuplicateOutbox() {
        engine.failOnce = true;
        assertEquals(0, relay.relayOnce());
        assertEquals("PENDING", state());
        assertEquals(1, engine.calls.get());
        jdbc.update("UPDATE flow_dispatch_outcome_outbox SET next_attempt_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", state());
        assertEquals(2, engine.calls.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_dispatch_outcome_outbox", Integer.class));
    }

    @Test
    void conflictingEngineReplyGoesToDeadLetter() {
        engine.conflict = true;
        assertEquals(0, relay.relayOnce());
        assertEquals("DEAD", state());
        assertEquals(0, relay.relayOnce());
    }

    @Test
    void inFlightLeaseIsNotStolenBeforeDeadlineButCanRecoverAfter() {
        jdbc.update("UPDATE flow_dispatch_outcome_outbox SET delivery_state='IN_FLIGHT', "
                + "claim_token=?, lease_until=TIMESTAMP '2999-01-01 00:00:00'", UUID.randomUUID().toString());
        assertEquals(0, relay.relayOnce());
        assertEquals(0, engine.calls.get());
        jdbc.update("UPDATE flow_dispatch_outcome_outbox SET lease_until=TIMESTAMP '2000-01-01 00:00:00'");
        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", state());
    }

    private String state() {
        return jdbc.queryForObject("SELECT delivery_state FROM flow_dispatch_outcome_outbox WHERE event_id=?",
                String.class, eventId);
    }

    private static final class FakeEngineRpc implements EngineRpc {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failOnce;
        volatile boolean conflict;
        @Override public EngineFireReply acceptScheduledFire(ScheduleFireCommand unused) { throw new UnsupportedOperationException(); }
        @Override public EngineRunListReply listRuns(String owner, String project) { throw new UnsupportedOperationException(); }
        @Override public EngineOutcomeReply acknowledgeTaskOutcome(EngineOutcomeCommand event) {
            int called = calls.incrementAndGet();
            if (failOnce && called == 1) throw new IllegalStateException("network-ack-lost");
            if (conflict) return new EngineOutcomeReply("CONFLICT", null, null);
            return new EngineOutcomeReply("ACCEPTED", event.runId(), event.outcome());
        }
    }
}
