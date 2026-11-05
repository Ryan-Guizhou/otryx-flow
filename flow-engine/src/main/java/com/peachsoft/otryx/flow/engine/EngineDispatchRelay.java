package com.peachsoft.otryx.flow.engine;

import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchTaskCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
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
 * Engine Outbox：短事务领取，事务外 RPC 查询固定 JobRevision 并幂等发送到 Dispatcher。
 * <p>RPC 超时后同 runId 持久重投，直到确认 Dispatcher 已落库；不冒充 Worker 实际执行。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@Component
public class EngineDispatchRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineDispatchRelay.class);
    private static final int MAX_BATCH = 8;
    private static final long LEASE_SECONDS = 45;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;
    @OtryxRpcReference(version = "1.0.0")
    private JobRpc jobs;

    /** Engine 私有 DataSource；所有 RPC 严格位于数据库事务之外。 */
    public EngineDispatchRelay(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
    }

    /** 周期性有界尝试；故障不妨碍上游 Inbox/Run 持久化。 */
    @Scheduled(fixedDelayString = "${flow.engine.dispatch-poll-ms:1000}")
    public void poll() {
        try {
            int acked = relayOnce();
            if (acked > 0) LOGGER.info("Engine dispatched {} durable runs", acked);
        } catch (RuntimeException failure) {
            LOGGER.warn("Engine dispatch relay failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** 每次最多八个任务，失败时保持重投权，ACK 必须带当前 claimToken。 */
    public int relayOnce() {
        int sent = 0;
        for (Claimed claim : claimBatch()) {
            try {
                Run run = getRun(claim.runId());
                if (run == null || !claim.eventId().equals(run.eventId())) {
                    quarantine(claim, "RUN_SNAPSHOT_MISSING");
                    continue;
                }
                if ("CANCEL_REQUESTED".equals(run.status()) || "CANCELLED".equals(run.status())) {
                    dispatcher.cancelTask(new DispatchTaskCancelCommand(run.owner(), run.project(), run.id()));
                    ackCancelled(claim);
                    continue;
                }
                JobRevisionView job = jobs.revision(run.owner(), run.project(), run.jobId(), run.jobRevision());
                if (job == null) {
                    quarantine(claim, "PUBLISHED_REVISION_MISSING");
                    continue;
                }
                String parameters = overrideParameters(run.id(), job.parametersJson());
                DispatchEnqueueReply result = dispatcher.enqueue(new DispatchEnqueueCommand(
                        run.id(), run.eventId(), run.owner(), run.project(), run.scheduleId(), run.jobId(),
                        run.jobRevision(), job.handlerKey(), parameters,
                        job.timeoutSeconds(), job.maxAttempts(), "JOB"));
                if (result == null) throw new IllegalStateException("Missing Dispatcher result");
                if ("CREATED".equals(result.code()) || "DUPLICATE".equals(result.code())) {
                    if (ack(claim)) sent++;
                } else if ("CONFLICT".equals(result.code())) {
                    Run again = getRun(claim.runId());
                    if (again != null && ("CANCEL_REQUESTED".equals(again.status())
                            || "CANCELLED".equals(again.status()))) {
                        ackCancelled(claim);
                    } else {
                        quarantine(claim, "DISPATCHER_CONFLICT");
                    }
                } else if ("CAPACITY_WAIT".equals(result.code())) {
                    retry(claim, result.code());
                } else if ("CAPACITY_REJECTED".equals(result.code())) {
                    rejectCapacity(claim);
                } else {
                    retry(claim, "DISPATCHER_UNKNOWN_REPLY");
                }
            } catch (RuntimeException unavailable) {
                retry(claim, unavailable.getClass().getSimpleName());
            }
        }
        return sent;
    }

    private List<Claimed> claimBatch() {
        List<Claimed> batch = tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<Pending> due = jdbc.query("SELECT run_id, event_id, attempts "
                            + "FROM flow_engine_dispatch_outbox WHERE "
                            + "(delivery_state = 'PENDING' AND next_attempt_at <= ?) OR "
                            + "(delivery_state = 'IN_FLIGHT' AND lease_until <= ?) "
                            + "ORDER BY created_at, run_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> new Pending(rs.getString("run_id"), rs.getString("event_id"),
                            rs.getInt("attempts")), now, now, MAX_BATCH);
            List<Claimed> result = new ArrayList<>(due.size());
            for (Pending pending : due) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("UPDATE flow_engine_dispatch_outbox "
                                + "SET delivery_state = 'IN_FLIGHT', claim_token = ?, lease_until = ?, "
                                + "attempts = attempts + 1 WHERE run_id = ? "
                                + "AND delivery_state IN ('PENDING','IN_FLIGHT')",
                        token, Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS)), pending.runId());
                if (changed != 1) throw new IllegalStateException("Engine outbox claim CAS lost");
                result.add(new Claimed(pending.runId(), pending.eventId(), token, pending.attempts() + 1));
            }
            return result;
        });
        return batch == null ? List.of() : batch;
    }

    private boolean ack(Claimed claimed) {
        Boolean confirmed = tx.execute(ignored -> {
            int changed = jdbc.update("UPDATE flow_engine_dispatch_outbox "
                            + "SET delivery_state = 'DELIVERED', claim_token = NULL, lease_until = NULL, "
                            + "last_error = NULL, delivered_at = CURRENT_TIMESTAMP(3) "
                            + "WHERE run_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                    claimed.runId(), claimed.token());
            if (changed == 0) return false;
            int advanced = jdbc.update("UPDATE flow_workflow_run SET status = 'DISPATCHED', "
                            + "updated_at = CURRENT_TIMESTAMP(3) "
                            + "WHERE id = ? AND status = 'WAITING_DISPATCH'",
                    claimed.runId());
            if (advanced == 0) {
                String persisted = jdbc.queryForObject(
                        "SELECT status FROM flow_workflow_run WHERE id = ?",
                        String.class, claimed.runId());
                if (!("DISPATCHED".equals(persisted) || "SUCCEEDED".equals(persisted)
                        || "FAILED".equals(persisted) || "CANCEL_REQUESTED".equals(persisted)
                        || "CANCELLED".equals(persisted))) {
                    throw new IllegalStateException("Run disappeared during dispatch ACK");
                }
            }
            return true;
        });
        return Boolean.TRUE.equals(confirmed);
    }

    private void rejectCapacity(Claimed claim) {
        tx.execute(ignored -> {
            Timestamp now = dbNow();
            int changed = jdbc.update("UPDATE flow_engine_dispatch_outbox SET delivery_state='DEAD', "
                            + "claim_token=NULL, lease_until=NULL, last_error='CAPACITY_REJECTED' "
                            + "WHERE run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                    claim.runId(), claim.token());
            if (changed == 1) {
                jdbc.update("UPDATE flow_workflow_run SET status='FAILED', failure_code='CAPACITY_REJECTED', "
                                + "finished_at=?, run_version=run_version+1, updated_at=? "
                                + "WHERE id=? AND status='WAITING_DISPATCH'",
                        now, now, claim.runId());
            }
            return null;
        });
    }

    private void retry(Claimed claim, String reason) {
        long seconds = Math.min(1L << Math.min(claim.attempts(), 6), 60L);
        jdbc.update("UPDATE flow_engine_dispatch_outbox SET delivery_state = 'PENDING', "
                        + "claim_token = NULL, lease_until = NULL, next_attempt_at = ?, last_error = ? "
                        + "WHERE run_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(seconds)), truncate(reason), claim.runId(), claim.token());
    }

    private void quarantine(Claimed claim, String reason) {
        int updated = jdbc.update("UPDATE flow_engine_dispatch_outbox SET delivery_state = 'DEAD', "
                        + "claim_token = NULL, lease_until = NULL, last_error = ? "
                        + "WHERE run_id = ? AND claim_token = ? AND delivery_state = 'IN_FLIGHT'",
                reason, claim.runId(), claim.token());
        if (updated == 1) {
            LOGGER.error("Engine dispatch outbox quarantined: runId={}, reason={}", claim.runId(), reason);
        }
    }

    private Run getRun(String id) {
        List<Run> rows = jdbc.query("SELECT * FROM flow_workflow_run WHERE id = ?",
                (rs, row) -> mapRun(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Timestamp dbNow() {
        Timestamp db = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (db == null) throw new IllegalStateException("Engine DB time unavailable");
        return db;
    }

    private static String truncate(String value) {
        if (value == null) return "UNKNOWN";
        return value.length() > 120 ? value.substring(0, 120) : value;
    }
    private boolean ackCancelled(Claimed claimed) {
        Integer changed = jdbc.update("UPDATE flow_engine_dispatch_outbox SET delivery_state='DELIVERED', "
                        + "claim_token=NULL, lease_until=NULL, last_error=NULL, delivered_at=CURRENT_TIMESTAMP(3) "
                        + "WHERE run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                claimed.runId(), claimed.token());
        return changed != null && changed == 1;
    }

    private String overrideParameters(String runId, String published) {
        List<String> rows = jdbc.query("SELECT parameter_override FROM flow_workflow_run WHERE id=?",
                (rs, index) -> rs.getString(1), runId);
        if (rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank()) return published;
        return rows.getFirst();
    }

    private static Run mapRun(ResultSet rs) throws SQLException {
        return new Run(rs.getString("id"), rs.getString("event_id"), rs.getString("owner_username"),
                rs.getString("project_id"), rs.getString("schedule_id"), rs.getString("job_id"),
                rs.getInt("job_revision"), rs.getString("status"));
    }
    private record Pending(String runId, String eventId, int attempts) { }
    private record Claimed(String runId, String eventId, String token, int attempts) { }
    private record Run(String id, String eventId, String owner, String project, String scheduleId,
                       String jobId, int jobRevision, String status) { }
}
