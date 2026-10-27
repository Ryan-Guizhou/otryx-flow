package com.peachsoft.otryx.flow.engine.dag;

import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Engine 可靠取消 Outbox：网络请求发生在短数据库事务外。
 *
 * <p>Dispatcher 以 UNIQUE(engine_run_id) 持久取消栅栏 fencing 未派发任务和租约；
 * 已经提交的 Worker 终态不能由取消意图覆盖。
 */
@Component
public class DagCancelRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(DagCancelRelay.class);
    private static final int BATCH = 8;
    private static final int MAX_ATTEMPTS = 24;
    private static final long CLAIM_SECONDS = 45;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DagRunCoordinator coordinator;
    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;
    @Autowired(required = false)
    private MeterRegistry meters;

    /** 事务仅属于 Engine，不访问 Dispatcher 私有表。 */
    public DagCancelRelay(JdbcTemplate jdbc, PlatformTransactionManager manager,
            DagRunCoordinator coordinator) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
        this.coordinator = coordinator;
    }

    /** 有界批次扫描，即使重启也可在过期租约后恢复。 */
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        try {
            int completed = relayOnce();
            if (completed > 0) LOGGER.info("DAG cancel relay acknowledged {} nodes", completed);
        } catch (RuntimeException error) {
            LOGGER.warn("DAG cancel relay failed: errorType={}", error.getClass().getSimpleName());
        }
    }

    /** 测试及监控可主动执行单次短事务 claim + RPC + ACK。 */
    public int relayOnce() {
        int acked = 0;
        for (Claim claim : claimBatch()) {
            try {
                Payload data = payload(claim.nodeRunId());
                if (data == null) {
                    quarantine(claim, "DAG_CANCEL_NODE_MISSING");
                    continue;
                }
                DispatchCancelReply response = dispatcher.cancelDagTask(new DispatchCancelCommand(
                        claim.nodeRunId(), data.owner(), data.project(),
                        data.dagRunId(), data.workflowId(), data.revision()));
                if (response == null) throw new IllegalStateException("Missing cancel RPC ACK");
                if ("CANCELLED".equals(response.code())) {
                    if (ack(claim, data, true)) acked++;
                } else if ("TERMINAL".equals(response.code())) {
                    // Dispatcher has a durable Worker outcome; let its existing Outcome Outbox deliver
                    // the SUCCEEDED/FAILED event instead of claiming the node was cancelled.
                    if (ack(claim, data, false)) acked++;
                } else if ("CONFLICT".equals(response.code())) {
                    quarantine(claim, "DAG_CANCEL_IDENTITY_CONFLICT");
                } else {
                    retry(claim, "DAG_CANCEL_UNKNOWN_REPLY");
                }
            } catch (RuntimeException error) {
                retry(claim, error.getClass().getSimpleName());
            }
        }
        return acked;
    }

    private List<Claim> claimBatch() {
        List<Claim> selected = tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<Pending> due = jdbc.query(
                    "SELECT node_run_id,attempts FROM flow_dag_cancel_outbox "
                            + "WHERE (delivery_state='PENDING' AND next_attempt_at<=?) "
                            + "OR (delivery_state='IN_FLIGHT' AND lease_until<=?) "
                            + "ORDER BY created_at,node_run_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, index) -> new Pending(rs.getString(1), rs.getInt(2)), now, now, BATCH);
            List<Claim> batch = new ArrayList<>();
            for (Pending row : due) {
                String token = UUID.randomUUID().toString();
                int updated = jdbc.update("UPDATE flow_dag_cancel_outbox SET "
                                + "delivery_state='IN_FLIGHT',claim_token=?,lease_until=?,"
                                + "attempts=attempts+1 WHERE node_run_id=? "
                                + "AND delivery_state IN ('PENDING','IN_FLIGHT')",
                        token, Timestamp.from(now.toInstant().plusSeconds(CLAIM_SECONDS)), row.nodeId());
                if (updated != 1) throw new IllegalStateException("Cancel claim CAS lost");
                batch.add(new Claim(row.nodeId(), token, row.attempts() + 1));
            }
            return batch;
        });
        return selected == null ? List.of() : selected;
    }

    private Payload payload(String nodeId) {
        List<Payload> rows = jdbc.query(
                "SELECT r.id,r.owner_username,r.project_id,r.workflow_id,r.workflow_revision "
                        + "FROM flow_dag_node_run n JOIN flow_dag_run r ON r.id=n.dag_run_id "
                        + "WHERE n.id=?",
                (rs, idx) -> new Payload(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5)), nodeId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean ack(Claim claim, Payload payload, boolean confirmedCancelled) {
        Boolean done = tx.execute(ignored -> {
            coordinator.lockRunProject(payload.dagRunId());
            int changed = jdbc.update("UPDATE flow_dag_cancel_outbox "
                            + "SET delivery_state='DELIVERED',claim_token=NULL,lease_until=NULL,"
                            + "last_error=NULL,delivered_at=CURRENT_TIMESTAMP(3) "
                            + "WHERE node_run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                    claim.nodeRunId(), claim.token());
            if (changed != 1) return false;
            if (confirmedCancelled) {
                jdbc.update("UPDATE flow_dag_node_run SET status='CANCELLED',"
                                + "updated_at=CURRENT_TIMESTAMP(3) "
                                + "WHERE id=? AND status='CANCEL_PENDING'",
                        claim.nodeRunId());
            }
            coordinator.reconcileCancellationLocked(payload.dagRunId());
            return true;
        });
        if (Boolean.TRUE.equals(done) && confirmedCancelled && meters != null) {
            var duration = jdbc.query("SELECT r.cancel_requested_at FROM flow_dag_run r "
                            + "WHERE r.id=?", (rs, row) -> rs.getTimestamp(1), payload.dagRunId());
            if (!duration.isEmpty() && duration.getFirst() != null) {
                Timer.builder("flow.dag.cancel.ack.latency").register(meters)
                        .record(Duration.between(duration.getFirst().toInstant(), Instant.now()));
            }
        }
        return Boolean.TRUE.equals(done);
    }

    private void retry(Claim claim, String problem) {
        if (claim.attempts() >= MAX_ATTEMPTS) {
            quarantine(claim, "DAG_CANCEL_MAX_ATTEMPTS");
            return;
        }
        long backoff = Math.min(1L << Math.min(claim.attempts(), 6), 60L);
        jdbc.update("UPDATE flow_dag_cancel_outbox SET delivery_state='PENDING',"
                        + "claim_token=NULL,lease_until=NULL,next_attempt_at=?,last_error=? "
                        + "WHERE node_run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(backoff)), shorten(problem),
                claim.nodeRunId(), claim.token());
    }

    private void quarantine(Claim claim, String reason) {
        int rows = jdbc.update("UPDATE flow_dag_cancel_outbox SET delivery_state='DEAD',"
                        + "claim_token=NULL,lease_until=NULL,last_error=? "
                        + "WHERE node_run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                shorten(reason), claim.nodeRunId(), claim.token());
        if (rows > 0) {
            if (meters != null) meters.counter("flow.dag.cancel.dead.total").increment();
            LOGGER.error("DAG cancel quarantined: nodeRunId={},reason={}", claim.nodeRunId(), reason);
        }
    }

    private static String shorten(String reason) {
        if (reason == null) return "UNKNOWN";
        return reason.length() <= 120 ? reason : reason.substring(0, 120);
    }

    private Timestamp dbNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (now == null) throw new IllegalStateException("Engine DB clock unavailable");
        return now;
    }

    private record Pending(String nodeId, int attempts) { }
    private record Claim(String nodeRunId, String token, int attempts) { }
    private record Payload(String dagRunId, String owner, String project,
                           String workflowId, int revision) { }
}
