package com.peachsoft.otryx.flow.engine.dag;

import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
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
 * 每个 DAG NodeRun 独立可靠入队。复用 M2 Dispatcher 的稳定 runId 语义，
 * 以 nodeRunId 作为入队键；父 WorkflowRun 使用独立 DAG Run ID。
 */
@Component
public class DagDispatchRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(DagDispatchRelay.class);
    private static final int MAX_BATCH = 8;
    private static final int MAX_ATTEMPTS = 24;
    private static final long LEASE_SECONDS = 45;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DagRunCoordinator coordinator;

    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;

    /** 所有 Outbox / NodeRun / WorkflowRun 更新仅使用 Engine 本地事务。 */
    public DagDispatchRelay(JdbcTemplate jdbc, PlatformTransactionManager manager,
            DagRunCoordinator coordinator) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
        this.coordinator = coordinator;
    }

    /** 后台轮询有界批次；丢失 RPC ACK 时依赖稳定 NodeRun ID 重投。 */
    @Scheduled(fixedDelay = 1000)
    public void poll() {
        try {
            int delivered = relayOnce();
            recoverDeadOnce();
            if (delivered > 0) LOGGER.info("DAG dispatched {} node runs", delivered);
        } catch (RuntimeException failure) {
            LOGGER.warn("DAG dispatch relay failed: errorType={}",
                    failure.getClass().getSimpleName());
        }
    }

    /** 仅 Dispatcher RPC 在 Engine 事务之外发生。 */
    public int relayOnce() {
        int delivered = 0;
        for (Claim claim : claimBatch()) {
            try {
                Payload payload = loadPayload(claim.nodeRunId());
                if (payload == null || !payload.valid()) {
                    dead(claim, "DAG_NODE_SNAPSHOT_MISSING");
                    continue;
                }
                // M2 scheduleId/jobId columns carry DAG Run ID / WorkflowDefinition ID here.
                // The actual unique Dispatcher execution identity is always nodeRunId.
                DispatchEnqueueReply result = dispatcher.enqueue(new DispatchEnqueueCommand(
                        claim.nodeRunId(), claim.eventId(), payload.owner(), payload.project(),
                        payload.workflowRunId(), payload.workflowId(), payload.workflowRevision(),
                        payload.handler(), payload.parameters(), payload.timeoutSeconds(),
                        payload.maxAttempts()));
                if (result == null) throw new IllegalStateException("Null Dispatcher RPC result");
                if ("CREATED".equals(result.code()) || "DUPLICATE".equals(result.code())) {
                    if (ack(claim)) delivered++;
                } else if ("CONFLICT".equals(result.code())) {
                    dead(claim, "NODE_DISPATCHER_CONFLICT");
                } else {
                    retry(claim, "UNEXPECTED_DISPATCH_REPLY");
                }
            } catch (RuntimeException remoteFailure) {
                retry(claim, remoteFailure.getClass().getSimpleName());
            }
        }
        return delivered;
    }

    private List<Claim> claimBatch() {
        List<Claim> claimed = tx.execute(ignored -> {
            Timestamp now = now();
            List<Pending> rows = jdbc.query(
                    "SELECT node_run_id,event_id,attempts FROM flow_dag_dispatch_outbox "
                            + "WHERE (delivery_state = 'PENDING' AND next_attempt_at <= ?) "
                            + "OR (delivery_state = 'IN_FLIGHT' AND lease_until <= ?) "
                            + "ORDER BY created_at,node_run_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> new Pending(rs.getString(1), rs.getString(2), rs.getInt(3)),
                    now, now, MAX_BATCH);
            List<Claim> selected = new ArrayList<>(rows.size());
            for (Pending pending : rows) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("UPDATE flow_dag_dispatch_outbox "
                                + "SET delivery_state = 'IN_FLIGHT', claim_token = ?, "
                                + "lease_until = ?, attempts = attempts + 1 WHERE node_run_id = ? "
                                + "AND delivery_state IN ('PENDING','IN_FLIGHT')",
                        token, Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS)),
                        pending.nodeId());
                if (changed != 1) throw new IllegalStateException("DAG dispatch claim CAS failed");
                selected.add(new Claim(pending.nodeId(), pending.eventId(),
                        token, pending.attempts() + 1));
            }
            return selected;
        });
        return claimed == null ? List.of() : claimed;
    }

    private Payload loadPayload(String nodeId) {
        List<Payload> found = jdbc.query(
                "SELECT n.status,n.node_type,n.handler_key,n.parameters_json,n.timeout_seconds,"
                        + "n.max_attempts,r.id AS workflow_run_id,r.owner_username,r.project_id,"
                        + "r.workflow_id,r.workflow_revision FROM flow_dag_node_run n "
                        + "JOIN flow_dag_run r ON r.id = n.dag_run_id WHERE n.id = ?",
                (rs, row) -> read(rs), nodeId);
        return found.isEmpty() ? null : found.getFirst();
    }

    private boolean ack(Claim claim) {
        Boolean accepted = tx.execute(ignored -> {
            int changed = jdbc.update("UPDATE flow_dag_dispatch_outbox "
                            + "SET delivery_state = 'DELIVERED', claim_token = NULL, lease_until = NULL, "
                            + "delivered_at = CURRENT_TIMESTAMP(3), last_error = NULL "
                            + "WHERE node_run_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                    claim.nodeRunId(), claim.token());
            if (changed != 1) return false;
            // A fast Worker may have already delivered terminal Outcome before this ACK.
            jdbc.update("UPDATE flow_dag_node_run "
                            + "SET status = 'DISPATCHED', updated_at = CURRENT_TIMESTAMP(3) "
                            + "WHERE id = ? AND status = 'READY'", claim.nodeRunId());
            return true;
        });
        return Boolean.TRUE.equals(accepted);
    }

    private void retry(Claim claim, String problem) {
        if (claim.attempts() >= MAX_ATTEMPTS) {
            dead(claim, "DAG_RETRY_BUDGET_EXHAUSTED");
            return;
        }
        long delay = Math.min(1L << Math.min(claim.attempts(), 6), 60L);
        jdbc.update("UPDATE flow_dag_dispatch_outbox SET delivery_state = 'PENDING', "
                        + "claim_token = NULL, lease_until = NULL, next_attempt_at = ?, last_error = ? "
                        + "WHERE node_run_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(delay)), trim(problem),
                claim.nodeRunId(), claim.token());
    }

    private void dead(Claim claim, String reason) {
        int changed = jdbc.update("UPDATE flow_dag_dispatch_outbox SET delivery_state = 'DEAD', "
                        + "claim_token = NULL, lease_until = NULL, last_error = ? "
                        + "WHERE node_run_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                trim(reason), claim.nodeRunId(), claim.token());
        if (changed == 1) {
            LOGGER.error("DAG dispatch quarantined: nodeRunId={}, reason={}", claim.nodeRunId(), reason);
            recoverDeadOnce();
        }
    }

    /** 自动恢复 DEAD Outbox 的半途崩溃窗口，直到父 DAG Run 收敛失败。 */
    public void recoverDeadOnce() {
        List<String[]> stuck = jdbc.query(
                "SELECT n.dag_run_id,n.id FROM flow_dag_dispatch_outbox o "
                        + "JOIN flow_dag_node_run n ON n.id = o.node_run_id "
                        + "WHERE o.delivery_state = 'DEAD' AND n.status IN ('READY','DISPATCHED') "
                        + "ORDER BY o.created_at LIMIT ?",
                (rs, row) -> new String[]{rs.getString(1),rs.getString(2)}, MAX_BATCH);
        for (String[] row : stuck) {
            tx.execute(ignored -> {
                coordinator.failDispatchLocked(row[0], row[1]);
                return null;
            });
        }
    }

    private static String trim(String reason) {
        if (reason == null) return "UNKNOWN";
        return reason.length() > 120 ? reason.substring(0, 120) : reason;
    }

    private Timestamp now() {
        Timestamp instant = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (instant == null) throw new IllegalStateException("Engine database time unavailable");
        return instant;
    }

    private static Payload read(ResultSet rs) throws SQLException {
        return new Payload(rs.getString("status"), rs.getString("node_type"),
                rs.getString("handler_key"), rs.getString("parameters_json"),
                rs.getInt("timeout_seconds"), rs.getInt("max_attempts"),
                rs.getString("workflow_run_id"), rs.getString("owner_username"),
                rs.getString("project_id"), rs.getString("workflow_id"),
                rs.getInt("workflow_revision"));
    }

    private record Pending(String nodeId, String eventId, int attempts) { }
    private record Claim(String nodeRunId, String eventId, String token, int attempts) { }
    private record Payload(String status, String type, String handler, String parameters,
                           int timeoutSeconds, int maxAttempts, String workflowRunId, String owner,
                           String project, String workflowId, int workflowRevision) {
        private boolean valid() {
            return "TASK".equals(type) && ("READY".equals(status) || "DISPATCHED".equals(status)
                    || "SUCCEEDED".equals(status) || "FAILED".equals(status))
                    && handler != null && parameters != null && timeoutSeconds > 0
                    && maxAttempts > 0 && workflowRevision > 0;
        }
    }
}
