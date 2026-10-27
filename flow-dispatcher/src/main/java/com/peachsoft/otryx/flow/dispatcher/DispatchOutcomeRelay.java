package com.peachsoft.otryx.flow.dispatcher;

import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * Dispatcher 终态事件可靠投递：先以短事务认领 Outbox，事务外调用 Engine RPC，
 * 再由当前 claimToken CAS 确认交付。发送超时不会变成假成功；同 eventId 重投安全。
 */
@Component
public class DispatchOutcomeRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchOutcomeRelay.class);
    private static final int MAX_BATCH = 8;
    private static final long LEASE_SECONDS = 45;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @OtryxRpcReference(version = "1.0.0")
    private EngineRpc engine;

    /** 仅绑定 Dispatcher 自有的数据库事务。 */
    public DispatchOutcomeRelay(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
    }

    /** 周期扫描最多八条，网络故障仅回退 Outbox 状态，不回退已提交的业务终态。 */
    @Scheduled(fixedDelayString = "${flow.dispatcher.outcome-poll-ms:1000}")
    public void poll() {
        try {
            int acked = relayOnce();
            if (acked > 0) LOGGER.info("Dispatcher delivered {} terminal outcomes", acked);
        } catch (RuntimeException failure) {
            LOGGER.warn("Dispatcher outcome relay failed: errorType={}", failure.getClass().getSimpleName());
        }
    }

    /** 故障注入单元测试使用的单批次可靠发送。 */
    public int relayOnce() {
        int delivered = 0;
        for (Claimed row : claimBatch()) {
            try {
                EngineOutcomeReply reply = engine.acknowledgeTaskOutcome(
                        new EngineOutcomeCommand(row.eventId(), row.runId(), row.taskId(),
                                row.projectId(), row.epoch(), row.outcome()));
                if (reply == null) throw new IllegalStateException("Engine outcome reply missing");
                if (("ACCEPTED".equals(reply.code()) || "DUPLICATE".equals(reply.code()))
                        && row.runId().equals(reply.runId()) && row.outcome().equals(reply.runStatus())) {
                    if (ack(row)) delivered++;
                } else if ("CONFLICT".equals(reply.code())) {
                    quarantine(row, "ENGINE_OUTCOME_CONFLICT");
                } else {
                    retry(row, "ENGINE_UNEXPECTED_REPLY");
                }
            } catch (RuntimeException unavailable) {
                retry(row, unavailable.getClass().getSimpleName());
            }
        }
        return delivered;
    }

    private List<Claimed> claimBatch() {
        List<Claimed> claims = tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<Pending> rows = jdbc.query("SELECT event_id, engine_run_id, task_id, project_id, "
                            + "lease_epoch, outcome, attempts FROM flow_dispatch_outcome_outbox "
                            + "WHERE (delivery_state = 'PENDING' AND next_attempt_at <= ?) "
                            + "OR (delivery_state = 'IN_FLIGHT' AND lease_until <= ?) "
                            + "ORDER BY created_at, event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, index) -> pending(rs), now, now, MAX_BATCH);
            List<Claimed> picked = new ArrayList<>(rows.size());
            for (Pending p : rows) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("UPDATE flow_dispatch_outcome_outbox SET "
                                + "delivery_state='IN_FLIGHT', claim_token=?, lease_until=?, "
                                + "attempts=attempts+1 WHERE event_id=? "
                                + "AND delivery_state IN ('PENDING','IN_FLIGHT')",
                        token, Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS)), p.eventId());
                if (changed != 1) throw new IllegalStateException("Outcome Outbox claim CAS lost");
                picked.add(new Claimed(p.eventId(), p.runId(), p.taskId(), p.projectId(),
                        p.epoch(), p.outcome(), token, p.attempts() + 1));
            }
            return picked;
        });
        return claims == null ? List.of() : claims;
    }

    private boolean ack(Claimed claim) {
        int count = jdbc.update("UPDATE flow_dispatch_outcome_outbox SET delivery_state='DELIVERED', "
                        + "claim_token=NULL, lease_until=NULL, last_error=NULL, "
                        + "delivered_at=CURRENT_TIMESTAMP(3) WHERE event_id=? "
                        + "AND claim_token=? AND delivery_state='IN_FLIGHT'",
                claim.eventId(), claim.token());
        return count == 1;
    }

    private void retry(Claimed claim, String problem) {
        long backoff = Math.min(1L << Math.min(claim.attempts(), 6), 60L);
        jdbc.update("UPDATE flow_dispatch_outcome_outbox SET delivery_state='PENDING', "
                        + "claim_token=NULL, lease_until=NULL, next_attempt_at=?, last_error=? "
                        + "WHERE event_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(backoff)), shorten(problem), claim.eventId(), claim.token());
    }

    private void quarantine(Claimed claim, String reason) {
        int changed = jdbc.update("UPDATE flow_dispatch_outcome_outbox SET delivery_state='DEAD', "
                        + "claim_token=NULL, lease_until=NULL, last_error=? "
                        + "WHERE event_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                shorten(reason), claim.eventId(), claim.token());
        if (changed == 1) LOGGER.error("Dispatcher outcome quarantined: eventId={}, reason={}", claim.eventId(), reason);
    }

    private Timestamp dbNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (now == null) throw new IllegalStateException("Dispatcher DB clock unavailable");
        return now;
    }

    private static String shorten(String value) {
        if (value == null) return "UNKNOWN";
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private static Pending pending(ResultSet rs) throws SQLException {
        return new Pending(rs.getString("event_id"), rs.getString("engine_run_id"),
                rs.getString("task_id"), rs.getString("project_id"), rs.getLong("lease_epoch"),
                rs.getString("outcome"), rs.getInt("attempts"));
    }

    private record Pending(String eventId, String runId, String taskId, String projectId,
                           long epoch, String outcome, int attempts) { }
    private record Claimed(String eventId, String runId, String taskId, String projectId,
                           long epoch, String outcome, String token, int attempts) { }
}
