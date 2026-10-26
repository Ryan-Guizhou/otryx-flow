package com.peachsoft.otryx.flow.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.EngineFireReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox 转发器：短事务 Claim + Lease；事务提交后再做 OTRYX RPC；按 token CAS 确认。
 * 调用超时代表结果未知，不丢弃事件；Engine 的 Inbox 是最终去重权威。
 */
@Component
public class ScheduleOutboxRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleOutboxRelay.class);
    private static final int CLAIM_BATCH = 8;
    private static final long LEASE_SECONDS = 45L;
    private static final long MAX_BACKOFF_SECONDS = 60L;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    @OtryxRpcReference(version = "1.0.0")
    private EngineRpc engine;

    /** 使用 Scheduler 私有数据库事务，禁止跨 Scheduler/Engine 数据源原子提交的假设。 */
    public ScheduleOutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
        this.mapper = mapper;
    }

    /** 固定延迟轮询；故障只阻塞投递，不影响已落库逻辑火点。 */
    @Scheduled(fixedDelayString = "${flow.scheduler.relay-poll-ms:1000}")
    public void poll() {
        try {
            int delivered = relayOnce();
            if (delivered > 0) LOGGER.info("Delivered {} schedule outbox events to Engine", delivered);
        } catch (RuntimeException exception) {
            LOGGER.warn("Schedule outbox poll failed: {}", exception.getClass().getSimpleName());
        }
    }

    /**
     * 领取有界批次并在所有事务外调用远程 Engine。允许多副本同时调用。
     * @return 本轮已成功确认的 Outbox 数量
     */
    public int relayOnce() {
        List<Claimed> claimed = claimBatch();
        int delivered = 0;
        for (Claimed item : claimed) {
            try {
                ScheduleFireCommand event = mapper.readValue(item.payloadJson(), ScheduleFireCommand.class);
                if (!item.eventId().equals(event.eventId())) {
                    quarantine(item, "PAYLOAD_EVENT_ID_MISMATCH");
                    continue;
                }
                EngineFireReply reply = engine.acceptScheduledFire(event);
                if (reply == null) throw new IllegalStateException("Null Engine RPC reply");
                if ("CREATED".equals(reply.code()) || "DUPLICATE".equals(reply.code())) {
                    if (confirm(item)) delivered++;
                } else if ("CONFLICT".equals(reply.code())) {
                    quarantine(item, "ENGINE_REJECTED_CONFLICT");
                } else {
                    failRetry(item, "ENGINE_UNKNOWN_RESPONSE");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                quarantine(item, "INVALID_OUTBOX_PAYLOAD");
            } catch (RuntimeException unavailable) {
                // RPC timeout or lost ACK is not evidence that Engine failed to commit.
                failRetry(item, unavailable.getClass().getSimpleName());
            }
        }
        return delivered;
    }

    private List<Claimed> claimBatch() {
        List<Claimed> batch = tx.execute(ignored -> {
            Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
            if (now == null) throw new IllegalStateException("Database clock unavailable");
            List<Queued> due = jdbc.query("SELECT event_id, trigger_id, payload_json, attempts "
                            + "FROM flow_schedule_outbox WHERE "
                            + "(delivery_state = 'PENDING' AND next_attempt_at <= ?) OR "
                            + "(delivery_state = 'IN_FLIGHT' AND lease_until <= ?) "
                            + "ORDER BY created_at, event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> new Queued(rs.getString("event_id"), rs.getString("trigger_id"),
                            rs.getString("payload_json"), rs.getInt("attempts")),
                    now, now, CLAIM_BATCH);
            List<Claimed> result = new ArrayList<>(due.size());
            for (Queued row : due) {
                String token = UUID.randomUUID().toString();
                int n = jdbc.update("UPDATE flow_schedule_outbox SET delivery_state = 'IN_FLIGHT', "
                                + "claim_token = ?, lease_until = ?, attempts = attempts + 1 "
                                + "WHERE event_id = ? AND delivery_state IN ('PENDING', 'IN_FLIGHT')",
                        token, Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS)), row.eventId());
                if (n != 1) throw new IllegalStateException("Outbox claim CAS rejected");
                result.add(new Claimed(row.eventId(), row.triggerId(), row.payloadJson(),
                        token, row.attempts() + 1));
            }
            return result;
        });
        return batch == null ? List.of() : batch;
    }

    private boolean confirm(Claimed item) {
        Boolean ack = tx.execute(ignored -> {
            int changed = jdbc.update("UPDATE flow_schedule_outbox "
                            + "SET delivery_state = 'DELIVERED', claim_token = NULL, lease_until = NULL, "
                            + "delivered_at = CURRENT_TIMESTAMP(3), last_error = NULL "
                            + "WHERE event_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                    item.eventId(), item.token());
            if (changed == 0) return false; // Lost lease: newer delivery attempt is authoritative.
            int updated = jdbc.update("UPDATE flow_schedule_trigger SET status = 'ENGINE_ACCEPTED' "
                            + "WHERE id = ? AND status IN ('PENDING_ENGINE', 'ENGINE_ACCEPTED')",
                    item.triggerId());
            if (updated != 1) throw new IllegalStateException("Matching logical Trigger missing");
            return true;
        });
        return Boolean.TRUE.equals(ack);
    }

    private void failRetry(Claimed item, String error) {
        long exponential = 1L << Math.min(item.attempts(), 6);
        long backoff = Math.min(exponential, MAX_BACKOFF_SECONDS);
        int changed = jdbc.update("UPDATE flow_schedule_outbox "
                        + "SET delivery_state = 'PENDING', claim_token = NULL, lease_until = NULL, "
                        + "next_attempt_at = ?, last_error = ? "
                        + "WHERE event_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(backoff)), truncate(error),
                item.eventId(), item.token());
        if (changed == 1 && item.attempts() >= 5) {
            LOGGER.warn("Engine delivery will retry: eventId={}, attempts={}, error={}",
                    item.eventId(), item.attempts(), truncate(error));
        }
    }

    private void quarantine(Claimed item, String reason) {
        int updated = jdbc.update("UPDATE flow_schedule_outbox SET delivery_state = 'DEAD', "
                        + "claim_token = NULL, lease_until = NULL, last_error = ? "
                        + "WHERE event_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                truncate(reason), item.eventId(), item.token());
        if (updated == 1) {
            LOGGER.error("Scheduler outbox event quarantined: eventId={}, reason={}", item.eventId(), reason);
        }
    }

    private static String truncate(String error) {
        if (error == null) return "UNKNOWN";
        return error.length() <= 120 ? error : error.substring(0, 120);
    }

    private record Queued(String eventId, String triggerId, String payloadJson, int attempts) { }
    private record Claimed(String eventId, String triggerId, String payloadJson,
                           String token, int attempts) { }
}
