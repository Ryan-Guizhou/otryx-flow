package com.peachsoft.otryx.flow.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.EngineFireReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** Scheduler 私有 Outbox 的 Claim、RPC 失败重试、过期 Claim 接管与 ACK fencing。 */
class ScheduleOutboxRelayTest {
    private JdbcTemplate jdbc;
    private ScheduleOutboxRelay relay;
    private EngineRpc engine;
    private String eventId;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:relay-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE flow_schedule_outbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "trigger_id VARCHAR(36), event_type VARCHAR(48), payload_json TEXT, "
                + "delivery_state VARCHAR(16), attempts INT, created_at TIMESTAMP(3), "
                + "delivered_at TIMESTAMP(3), claim_token VARCHAR(36), lease_until TIMESTAMP(3), "
                + "next_attempt_at TIMESTAMP(3), last_error VARCHAR(120))");
        jdbc.execute("CREATE TABLE flow_schedule_trigger (id VARCHAR(36) PRIMARY KEY, status VARCHAR(24))");
        eventId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO flow_schedule_trigger (id, status) VALUES (?, 'PENDING_ENGINE')", eventId);
        var msg = new ScheduleFireCommand(eventId, "admin", UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(), 2,
                "2026-10-26T08:00:00Z", "CRON");
        jdbc.update("INSERT INTO flow_schedule_outbox "
                        + "(event_id,trigger_id,event_type,payload_json,delivery_state,attempts,created_at,next_attempt_at) "
                        + "VALUES (?, ?, 'SCHEDULE_FIRE', ?, 'PENDING', 0, ?, ?)",
                eventId, eventId, new ObjectMapper().writeValueAsString(msg),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.EPOCH));
        relay = new ScheduleOutboxRelay(jdbc, new DataSourceTransactionManager(ds), new ObjectMapper());
        engine = mock(EngineRpc.class);
        ReflectionTestUtils.setField(relay, "engine", engine);
    }

    @Test
    void marksDeliveredAndEngineAcceptedOnlyAfterPositiveReply() {
        when(engine.acceptScheduledFire(any())).thenReturn(new EngineFireReply("CREATED", UUID.randomUUID().toString(),
                "WAITING_DISPATCH"));
        assertEquals(1, relay.relayOnce());
        assertEquals(0, relay.relayOnce());
        assertEquals("DELIVERED", state());
        assertEquals("ENGINE_ACCEPTED", jdbc.queryForObject(
                "SELECT status FROM flow_schedule_trigger WHERE id = ?", String.class, eventId));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM flow_schedule_outbox", Integer.class));
        verify(engine, times(1)).acceptScheduledFire(any());
    }

    @Test
    void missingAckRetainsRetryableEventAndSameIdIsRedelivered() {
        when(engine.acceptScheduledFire(any())).thenThrow(new IllegalStateException("lost ACK"))
                .thenReturn(new EngineFireReply("DUPLICATE", UUID.randomUUID().toString(), "WAITING_DISPATCH"));
        assertEquals(0, relay.relayOnce());
        assertEquals("PENDING", state());
        assertEquals("PENDING_ENGINE", jdbc.queryForObject(
                "SELECT status FROM flow_schedule_trigger WHERE id = ?", String.class, eventId));
        jdbc.update("UPDATE flow_schedule_outbox SET next_attempt_at = ?", Timestamp.from(Instant.EPOCH));
        assertEquals(1, relay.relayOnce());
        assertEquals("DELIVERED", state());
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM flow_schedule_outbox", Integer.class));
    }

    @Test
    void expiredClaimIsReclaimedAndOldAcknowledgementCannotWin() {
        jdbc.update("UPDATE flow_schedule_outbox SET delivery_state='IN_FLIGHT', "
                + "claim_token='expired-token', lease_until = ?", Timestamp.from(Instant.EPOCH));
        when(engine.acceptScheduledFire(any())).thenAnswer(ignored -> {
            jdbc.update("UPDATE flow_schedule_outbox SET claim_token='new-owner-token' WHERE event_id = ?", eventId);
            return new EngineFireReply("CREATED", UUID.randomUUID().toString(), "WAITING_DISPATCH");
        });
        assertEquals(0, relay.relayOnce());
        assertEquals("IN_FLIGHT", state());
        assertEquals("PENDING_ENGINE", jdbc.queryForObject(
                "SELECT status FROM flow_schedule_trigger WHERE id = ?", String.class, eventId));
    }

    @Test
    void conflictingPayloadIsQuarantinedWithoutReportingDelivery() {
        when(engine.acceptScheduledFire(any())).thenReturn(new EngineFireReply("CONFLICT", null, null));
        assertEquals(0, relay.relayOnce());
        assertEquals("DEAD", state());
        assertEquals(0, relay.relayOnce());
    }

    private String state() {
        return jdbc.queryForObject("SELECT delivery_state FROM flow_schedule_outbox", String.class);
    }
}
