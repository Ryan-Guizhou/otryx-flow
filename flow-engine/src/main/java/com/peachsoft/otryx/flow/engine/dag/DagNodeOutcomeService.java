package com.peachsoft.otryx.flow.engine.dag;

import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeReply;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 复用 M2 Dispatcher 终态协议：每个 DAG NodeRun 具有独立稳定 runId。
 *
 * <p>先锁父 DAG Run 再锁 NodeRun；Inbox 插入、节点终态及后继推进在同一
 * Engine 本地事务执行，不跨服务访问 Dispatcher 数据库。
 */
@Component
public class DagNodeOutcomeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DagRunCoordinator coordinator;

    /** 只连接 Engine 私有 DataSource 和事务管理器。 */
    public DagNodeOutcomeService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            DagRunCoordinator coordinator) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(8);
        this.coordinator = coordinator;
    }

    /**
     * @return 非 DAG NodeRun 返回 null，交给原有 M2 Engine 逻辑；已识别节点返回独立 ACK。
     */
    public EngineOutcomeReply tryAcknowledge(EngineOutcomeCommand command) {
        if (command == null || command.leaseEpoch() < 1
                || !("SUCCEEDED".equals(command.outcome()) || "FAILED".equals(command.outcome()))) {
            throw new IllegalArgumentException("Invalid DAG outcome");
        }
        String nodeId = validUuid(command.runId());
        String eventId = validUuid(command.eventId());
        String taskId = validUuid(command.taskId());
        String projectId = validUuid(command.projectId());
        List<String> owners = jdbc.query(
                "SELECT dag_run_id FROM flow_dag_node_run WHERE id = ?",
                (rs, row) -> rs.getString(1), nodeId);
        if (owners.isEmpty()) return null;
        String runId = owners.getFirst();
        String fingerprint = digest(eventId, nodeId, taskId, projectId,
                command.leaseEpoch(), command.outcome());
        try {
            return tx.execute(ignored -> {
                String ownedProject = coordinator.lockRunProject(runId);
                if (!projectId.equals(ownedProject)) return conflict();
                List<String> statuses = jdbc.query(
                        "SELECT status FROM flow_dag_node_run "
                                + "WHERE id = ? AND dag_run_id = ? FOR UPDATE",
                        (rs, row) -> rs.getString(1), nodeId, runId);
                if (statuses.isEmpty()) return conflict();
                List<String[]> previous = jdbc.query(
                        "SELECT event_id,payload_fingerprint,outcome FROM flow_dag_outcome_inbox "
                                + "WHERE node_run_id = ?",
                        (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)},
                        nodeId);
                if (!previous.isEmpty()) {
                    String[] old = previous.getFirst();
                    boolean same = eventId.equals(old[0]) && fingerprint.equals(old[1])
                            && command.outcome().equals(old[2]);
                    return same ? new EngineOutcomeReply("DUPLICATE", nodeId, old[2]) : conflict();
                }
                if (!("READY".equals(statuses.getFirst()) || "DISPATCHED".equals(statuses.getFirst()))) {
                    return conflict();
                }
                Integer staged = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flow_dag_dispatch_outbox WHERE node_run_id = ? "
                                + "AND delivery_state <> 'DEAD'",
                        Integer.class, nodeId);
                if (staged == null || staged != 1) return conflict();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_dag_outcome_inbox "
                                + "(event_id,node_run_id,task_id,project_id,lease_epoch,outcome,"
                                + "payload_fingerprint,accepted_at) VALUES (?,?,?,?,?,?,?,?)",
                        eventId, nodeId, taskId, projectId, command.leaseEpoch(), command.outcome(),
                        fingerprint, now);
                int updated = jdbc.update("UPDATE flow_dag_node_run "
                                + "SET status = ?, updated_at = ? WHERE id = ? "
                                + "AND status IN ('READY','DISPATCHED')",
                        command.outcome(), now, nodeId);
                if (updated != 1) throw new IllegalStateException("NodeRun terminal CAS lost");
                coordinator.advanceLocked(runId);
                return new EngineOutcomeReply("ACCEPTED", nodeId, command.outcome());
            });
        } catch (DuplicateKeyException race) {
            List<String[]> old = jdbc.query(
                    "SELECT event_id,payload_fingerprint,outcome FROM flow_dag_outcome_inbox "
                            + "WHERE node_run_id = ?",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3)}, nodeId);
            return !old.isEmpty() && eventId.equals(old.getFirst()[0])
                    && fingerprint.equals(old.getFirst()[1])
                    ? new EngineOutcomeReply("DUPLICATE", nodeId, old.getFirst()[2]) : conflict();
        }
    }

    private static EngineOutcomeReply conflict() {
        return new EngineOutcomeReply("CONFLICT", null, null);
    }

    private static String validUuid(String value) {
        if (value == null) throw new IllegalArgumentException("Missing DAG outcome UUID");
        try {
            String normalized = java.util.UUID.fromString(value).toString();
            if (normalized.equals(value)) return value;
        } catch (IllegalArgumentException ignored) {
            // uniform rejection
        }
        throw new IllegalArgumentException("Invalid DAG outcome UUID");
    }

    private static String digest(String event, String run, String task, String project,
            long epoch, String outcome) {
        String data = String.join("\u0000", event, run, task, project, Long.toString(epoch), outcome);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
