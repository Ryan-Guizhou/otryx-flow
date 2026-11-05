package com.peachsoft.otryx.flow.engine;

import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchTaskCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
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
 * 把持久化的 Run 取消意图可靠投递给 Dispatcher。
 *
 * <p>领取租约可回收；只有当前 claimToken 能确认或退回消息。收到 CANCEL_REQUESTED
 * 只表示 Dispatcher 已记录取消意图，不表示 Worker 已经退出。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/5 14:00
 */
@Component
public class JobRunCancelRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobRunCancelRelay.class);
    private static final int MAX_BATCH = 8;
    private static final long LEASE_SECONDS = 45L;
    private static final long MAX_BACKOFF_SECONDS = 60L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;

    /** Engine 私有事务；Dispatcher RPC 始终在事务外执行。 */
    public JobRunCancelRelay(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
    }

    /** 周期性恢复未投递及租约过期的取消请求。 */
    @Scheduled(fixedDelayString = "${flow.engine.cancel-poll-ms:1000}")
    public void poll() {
        try {
            int delivered = relayOnce();
            if (delivered > 0) LOGGER.info("Engine delivered {} cancellation requests", delivered);
        } catch (RuntimeException failure) {
            LOGGER.warn("Job cancel relay failed: errorType={}", failure.getClass().getSimpleName());
        }
    }

    /** 领取最多八条请求；租约过期后允许其他 Engine 副本接管。 */
    public int relayOnce() {
        List<Claimed> claimed = claimBatch();
        int delivered = 0;
        for (Claimed item : claimed) {
            try {
                DispatchCancelReply reply = dispatcher.cancelTask(
                        new DispatchTaskCancelCommand(item.owner(), item.project(), item.runId()));
                if (reply == null) {
                    retry(item, "EMPTY_REPLY");
                } else if ("CANCELLED".equals(reply.code())
                        || "CANCEL_REQUESTED".equals(reply.code())
                        || "TERMINAL".equals(reply.code())
                        || "DUPLICATE".equals(reply.code())) {
                    if (ack(item, reply)) delivered++;
                } else {
                    retry(item, reply.code());
                }
            } catch (RuntimeException failure) {
                retry(item, failure.getClass().getSimpleName());
            }
        }
        return delivered;
    }

    private List<Claimed> claimBatch() {
        List<Claimed> rows = tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<Pending> due = jdbc.query("SELECT run_id, owner_username, project_id, attempts "
                            + "FROM flow_engine_run_cancel_outbox WHERE "
                            + "(delivery_state='PENDING' AND next_attempt_at<=?) OR "
                            + "(delivery_state='IN_FLIGHT' AND lease_until<=?) "
                            + "ORDER BY created_at, run_id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, index) -> new Pending(rs.getString("run_id"), rs.getString("owner_username"),
                            rs.getString("project_id"), rs.getInt("attempts")),
                    now, now, MAX_BATCH);
            List<Claimed> claimed = new ArrayList<>(due.size());
            for (Pending row : due) {
                String token = UUID.randomUUID().toString();
                int changed = jdbc.update("UPDATE flow_engine_run_cancel_outbox SET "
                                + "delivery_state='IN_FLIGHT', claim_token=?, lease_until=?, attempts=attempts+1 "
                                + "WHERE run_id=? AND delivery_state IN ('PENDING','IN_FLIGHT')",
                        token, Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS)), row.runId());
                if (changed != 1) throw new IllegalStateException("Cancel outbox claim CAS lost");
                claimed.add(new Claimed(row.runId(), row.owner(), row.project(), token, row.attempts() + 1));
            }
            return claimed;
        });
        return rows == null ? List.of() : rows;
    }

    private boolean ack(Claimed item, DispatchCancelReply reply) {
        Boolean acknowledged = tx.execute(ignored -> {
            int changed = jdbc.update("UPDATE flow_engine_run_cancel_outbox SET "
                            + "delivery_state='DELIVERED', claim_token=NULL, lease_until=NULL, last_error=NULL "
                            + "WHERE run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                    item.runId(), item.token());
            if (changed != 1) return false;
            if ("CANCELLED".equals(reply.code()) || "CANCELLED".equals(reply.taskStatus())) {
                Timestamp now = dbNow();
                jdbc.update("UPDATE flow_workflow_run SET status='CANCELLED', control_state='CANCELLED', "
                                + "finished_at=?, run_version=run_version+1, updated_at=? "
                                + "WHERE id=? AND status='CANCEL_REQUESTED'",
                        now, now, item.runId());
            }
            return true;
        });
        return Boolean.TRUE.equals(acknowledged);
    }

    private void retry(Claimed item, String reason) {
        long seconds = Math.min(1L << Math.min(item.attempts(), 6), MAX_BACKOFF_SECONDS);
        jdbc.update("UPDATE flow_engine_run_cancel_outbox SET delivery_state='PENDING', "
                        + "claim_token=NULL, lease_until=NULL, next_attempt_at=?, last_error=? "
                        + "WHERE run_id=? AND claim_token=? AND delivery_state='IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(seconds)), truncate(reason),
                item.runId(), item.token());
    }

    private Timestamp dbNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (now == null) throw new IllegalStateException("Engine database clock unavailable");
        return now;
    }

    private static String truncate(String value) {
        if (value == null) return "UNKNOWN";
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    private record Pending(String runId, String owner, String project, int attempts) { }
    private record Claimed(String runId, String owner, String project, String token, int attempts) { }
}
