package com.peachsoft.otryx.flow.engine;

import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchTaskCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 把已持久化的取消意图交给 Dispatcher。RPC 在事务外调用。
 *
 * <p>Worker 已经提交的成功或失败不会被这次取消倒写。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@Component
public class JobRunCancelRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobRunCancelRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;

    /** Engine 私有事务。Dispatcher 调用发生在事务提交之后。 */
    public JobRunCancelRelay(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(8);
    }

    /** 有界重试取消投递。失败保留 PENDING，不把 Run 标成业务失败。 */
    @Scheduled(fixedDelayString = "${flow.engine.cancel-poll-ms:1000}")
    public void poll() {
        try {
            relayOnce();
        } catch (RuntimeException failure) {
            LOGGER.warn("Job cancel relay failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** 领取最多八条取消请求并在事务外调用 Dispatcher。 */
    public int relayOnce() {
        List<String[]> claimed = tx.execute(ignored -> {
            Timestamp now = Timestamp.from(Instant.now());
            List<String[]> due = jdbc.query("SELECT run_id, owner_username, project_id FROM "
                            + "flow_engine_run_cancel_outbox WHERE delivery_state='PENDING' "
                            + "AND next_attempt_at<=? ORDER BY created_at LIMIT 8 FOR UPDATE SKIP LOCKED",
                    (rs, index) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, now);
            for (String[] row : due) {
                jdbc.update("UPDATE flow_engine_run_cancel_outbox SET delivery_state='IN_FLIGHT', "
                                + "claim_token=?, lease_until=?, attempts=attempts+1 WHERE run_id=?",
                        UUID.randomUUID().toString(), Timestamp.from(now.toInstant().plusSeconds(45)), row[0]);
            }
            return due;
        });
        if (claimed == null) return 0;
        int done = 0;
        for (String[] row : claimed) {
            try {
                DispatchCancelReply reply = dispatcher.cancelTask(
                        new DispatchTaskCancelCommand(row[1], row[2], row[0]));
                if (reply != null && ("CANCELLED".equals(reply.code()) || "TERMINAL".equals(reply.code())
                        || "DUPLICATE".equals(reply.code()))) {
                    jdbc.update("UPDATE flow_engine_run_cancel_outbox SET delivery_state='DELIVERED', "
                            + "claim_token=NULL WHERE run_id=? AND delivery_state='IN_FLIGHT'", row[0]);
                    if ("CANCELLED".equals(reply.code())) {
                        jdbc.update("UPDATE flow_workflow_run SET status='CANCELLED', control_state='CANCELLED', "
                                        + "finished_at=?, updated_at=? WHERE id=? AND status='CANCEL_REQUESTED'",
                                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), row[0]);
                    }
                    done++;
                } else {
                    retry(row[0], reply == null ? "EMPTY" : reply.code());
                }
            } catch (RuntimeException failure) {
                retry(row[0], failure.getClass().getSimpleName());
            }
        }
        return done;
    }

    private void retry(String runId, String reason) {
        jdbc.update("UPDATE flow_engine_run_cancel_outbox SET delivery_state='PENDING', claim_token=NULL, "
                        + "next_attempt_at=?, last_error=? WHERE run_id=? AND delivery_state='IN_FLIGHT'",
                Timestamp.from(Instant.now().plusSeconds(2)),
                reason == null ? "UNKNOWN" : reason.substring(0, Math.min(120, reason.length())), runId);
    }
}
