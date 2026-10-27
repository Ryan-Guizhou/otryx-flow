package com.peachsoft.otryx.flow.engine.dag;

import com.peachsoft.otryx.flow.contracts.v1.DagCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagControlAuditView;
import com.peachsoft.otryx.flow.contracts.v1.DagControlHistoryReply;
import com.peachsoft.otryx.flow.contracts.v1.DagControlReply;
import com.peachsoft.otryx.flow.contracts.v1.DagRetryCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagRunView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DAG 控制面：仅在 Engine 本地数据库事务里修改取消意图、逻辑尝试和审计。
 *
 * <p>跨服务 Dispatcher 取消由独立可靠 Outbox 投递；本类绝不持锁等待 RPC。
 */
@Component
public class DagControlService {
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private static final int MAX_LOGICAL_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MILLIS = 2_000L;
    private static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";
    private static final String CANCELLED = "CANCELLED";
    private static final String CANCEL_PENDING = "CANCEL_PENDING";
    private static final String FAILED = "FAILED";
    private static final String RUNNING = "RUNNING";
    private static final String CONFLICT = "CONFLICT";
    private static final String NOT_FOUND = "NOT_FOUND";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DagRunCoordinator coordinator;

    /** 事务与调度器共享同一个 Engine 数据源。 */
    public DagControlService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            DagRunCoordinator coordinator) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(8);
        this.coordinator = coordinator;
    }

    /** 当前 Run 版本的幂等取消，持久创建远端 Worker 取消的可靠发送任务。 */
    public DagControlReply cancel(DagCancelCommand c) {
        if (c == null) throw new IllegalArgumentException("Missing cancel request");
        String owner = owner(c.ownerUsername()), project = uuid(c.projectId());
        String runId = uuid(c.runId()), key = key(c.requestKey());
        if (c.expectedRunVersion() < 1) return reply("INVALID", null, null, false);
        String fp = fingerprint("CANCEL", owner, project, runId,
                "", c.expectedRunVersion());
        return tx.execute(ignored -> {
            DagRunView before = lockedRun(owner, project, runId);
            if (before == null) return reply(NOT_FOUND, null, null, false);
            DagControlReply repeat = replay(owner, key, fp, before);
            if (repeat != null) return repeat;
            if (!RUNNING.equals(before.status()) || before.runVersion() != c.expectedRunVersion()) {
                return reply(CONFLICT, before, null, false);
            }
            Timestamp now = Timestamp.from(Instant.now());
            int updated = jdbc.update("UPDATE flow_dag_run SET status = ?, run_version = run_version + 1, "
                            + "cancel_requested_at = ?, updated_at = ? "
                            + "WHERE id = ? AND status = 'RUNNING' AND run_version = ?",
                    CANCEL_REQUESTED, now, now, runId, before.runVersion());
            if (updated != 1) return reply(CONFLICT, before, null, false);
            for (Node row : latest(runId)) {
                if ("BLOCKED".equals(row.status())) {
                    jdbc.update("UPDATE flow_dag_node_run SET status=?,updated_at=? WHERE id=? "
                            + "AND status='BLOCKED'", CANCELLED, now, row.id());
                } else if ("READY".equals(row.status()) || "DISPATCHED".equals(row.status())) {
                    jdbc.update("UPDATE flow_dag_node_run SET status=?,updated_at=? WHERE id=? "
                            + "AND status IN ('READY','DISPATCHED')", CANCEL_PENDING, now, row.id());
                    // A committed PENDING Outbox must never be dispatched after cancellation.
                    // IN_FLIGHT is fenced remotely by the Dispatcher CANCELLED tombstone.
                    jdbc.update("UPDATE flow_dag_dispatch_outbox SET delivery_state='CANCELLED',"
                            + "last_error='DAG_RUN_CANCEL_REQUESTED' "
                            + "WHERE node_run_id=? AND delivery_state='PENDING'", row.id());
                    jdbc.update("INSERT INTO flow_dag_cancel_outbox "
                            + "(node_run_id,delivery_state,attempts,created_at) "
                            + "VALUES (?,'PENDING',0,?)", row.id(), now);
                }
            }
            coordinator.reconcileCancellationLocked(runId);
            DagRunView result = view(owner, project, runId);
            audit(runId, owner, key, fp, "CANCEL", null, null,
                    before.runVersion(), result.runVersion(), now);
            return reply("OK", result, null, false);
        });
    }

    /**
     * 仅允许已最终 FAILED 且没有在途任务的 DAG 重试一个失败 Task Node。
     * 保留所有已完成/失败 NodeRun 为不可变历史，重试产生新的 logicalAttempt 和 ID。
     */
    public DagControlReply retry(DagRetryCommand c) {
        if (c == null) throw new IllegalArgumentException("Missing retry request");
        String owner = owner(c.ownerUsername()), project = uuid(c.projectId());
        String runId = uuid(c.runId()), key = key(c.requestKey());
        if (c.nodeId() == null || c.nodeId().isBlank() || c.nodeId().length() > 128
                || c.expectedRunVersion() < 1) return reply("INVALID", null, null, false);
        String fp = fingerprint("RETRY", owner, project, runId,
                c.nodeId(), c.expectedRunVersion());
        return tx.execute(ignored -> {
            DagRunView before = lockedRun(owner, project, runId);
            if (before == null) return reply(NOT_FOUND, null, null, false);
            DagControlReply repeat = replay(owner, key, fp, before);
            if (repeat != null) return repeat;
            if (!FAILED.equals(before.status()) || before.runVersion() != c.expectedRunVersion()) {
                return reply(CONFLICT, before, null, false);
            }
            List<Node> latest = latest(runId);
            if (latest.stream().filter(row -> FAILED.equals(row.status())).count() != 1
                    || latest.stream().anyMatch(row -> "READY".equals(row.status())
                            || "DISPATCHED".equals(row.status()) || CANCEL_PENDING.equals(row.status()))) {
                return reply(CONFLICT, before, null, false);
            }
            Node old = latest.stream().filter(row -> c.nodeId().equals(row.nodeId()))
                    .findFirst().orElse(null);
            if (old == null || !FAILED.equals(old.status()) || !"TASK".equals(old.type())
                    || old.attempt() >= MAX_LOGICAL_ATTEMPTS
                    || !("safe.echo".equals(old.handler()) || "safe.fail".equals(old.handler()))) {
                return reply(CONFLICT, before, null, false);
            }
            Timestamp now = Timestamp.from(Instant.now());
            if (now.getTime() - old.updatedAt().getTime() < RETRY_BACKOFF_MILLIS) {
                return reply(CONFLICT, before, null, false);
            }
            String nextId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO flow_dag_node_run (id,dag_run_id,node_id,logical_attempt,"
                            + "node_type,status,condition_decision,handler_key,parameters_json,"
                            + "timeout_seconds,max_attempts,created_at,updated_at) "
                            + "SELECT ?,dag_run_id,node_id,logical_attempt+1,node_type,"
                            + "'BLOCKED',NULL,handler_key,parameters_json,timeout_seconds,max_attempts,?,? "
                            + "FROM flow_dag_node_run WHERE id=? AND status='FAILED'",
                    nextId, now, now, old.id());
            int changed = jdbc.update("UPDATE flow_dag_run SET status='RUNNING',"
                            + "run_version=run_version+1,updated_at=? WHERE id=? AND status='FAILED' "
                            + "AND run_version=?",
                    now, runId, before.runVersion());
            if (changed != 1) throw new IllegalStateException("Retry Run version CAS lost");
            coordinator.advanceLocked(runId);
            DagRunView result = view(owner, project, runId);
            audit(runId, owner, key, fp, "RETRY", old.nodeId(), nextId,
                    before.runVersion(), result.runVersion(), now);
            return reply("OK", result, nextId, false);
        });
    }

    /** 仅公开当前用户当前项目的有界状态审计，不返回请求键/指纹。 */
    public DagControlHistoryReply history(String ownerUsername, String projectId, String runId) {
        String owner = owner(ownerUsername), project = uuid(projectId), id = uuid(runId);
        if (view(owner, project, id) == null) return new DagControlHistoryReply(new DagControlAuditView[0]);
        List<DagControlAuditView> events = jdbc.query(
                "SELECT action,node_id,old_version,new_version,node_run_id,created_at "
                        + "FROM flow_dag_control_audit WHERE dag_run_id=? AND owner_username=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT 100",
                (rs, row) -> new DagControlAuditView(rs.getString(1), rs.getString(2),
                        rs.getLong(3), rs.getLong(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant().toString()), id, owner);
        return new DagControlHistoryReply(events.toArray(DagControlAuditView[]::new));
    }

    private DagRunView lockedRun(String owner, String project, String id) {
        List<DagRunView> rows = jdbc.query(
                "SELECT * FROM flow_dag_run WHERE id=? AND owner_username=? AND project_id=? FOR UPDATE",
                (rs, idx) -> run(rs), id, owner, project);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private DagRunView view(String owner, String project, String id) {
        List<DagRunView> rows = jdbc.query(
                "SELECT * FROM flow_dag_run WHERE id=? AND owner_username=? AND project_id=?",
                (rs, idx) -> run(rs), id, owner, project);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static DagRunView run(ResultSet rs) throws SQLException {
        return new DagRunView(rs.getString("id"), rs.getString("project_id"),
                rs.getString("workflow_id"), rs.getInt("workflow_revision"),
                rs.getString("graph_sha256"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(),
                rs.getLong("run_version"));
    }

    private List<Node> latest(String runId) {
        return jdbc.query("SELECT n.id,n.node_id,n.node_type,n.status,n.logical_attempt,"
                        + "n.handler_key,n.updated_at FROM flow_dag_node_run n "
                        + "WHERE n.dag_run_id=? AND n.logical_attempt=(SELECT MAX(m.logical_attempt) "
                        + "FROM flow_dag_node_run m WHERE m.dag_run_id=n.dag_run_id AND m.node_id=n.node_id) "
                        + "ORDER BY n.node_id",
                (rs, index) -> new Node(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getTimestamp(7)),
                runId);
    }

    private DagControlReply replay(String owner, String key, String fp, DagRunView before) {
        List<String[]> existing = jdbc.query(
                "SELECT request_fingerprint,node_run_id FROM flow_dag_control_audit "
                        + "WHERE owner_username=? AND request_key=?",
                (rs, i) -> new String[]{rs.getString(1),rs.getString(2)}, owner, key);
        if (existing.isEmpty()) return null;
        String[] previous = existing.getFirst();
        return previous[0].equals(fp)
                ? reply("DUPLICATE", before, previous[1], true)
                : reply(CONFLICT, before, null, true);
    }

    private void audit(String runId, String owner, String key, String fp, String action,
            String nodeId, String nodeRunId, long oldVersion, long newVersion, Timestamp now) {
        jdbc.update("INSERT INTO flow_dag_control_audit "
                        + "(id,dag_run_id,owner_username,request_key,request_fingerprint,"
                        + "action,node_id,node_run_id,old_version,new_version,created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), runId, owner, key, fp, action,
                nodeId, nodeRunId, oldVersion, newVersion, now);
    }

    private static DagControlReply reply(String code, DagRunView run, String node, boolean repeated) {
        return new DagControlReply(code, run, node, repeated);
    }

    private static String owner(String value) {
        if (value == null || !OWNER.matcher(value).matches())
            throw new IllegalArgumentException("Invalid DAG owner");
        return value;
    }

    private static String key(String value) {
        if (value == null || !KEY.matcher(value).matches())
            throw new IllegalArgumentException("Invalid control request key");
        return value;
    }

    private static String uuid(String value) {
        if (value != null) {
            try { if (UUID.fromString(value).toString().equals(value)) return value; }
            catch (IllegalArgumentException invalid) { /* uniform rejection */ }
        }
        throw new IllegalArgumentException("Invalid DAG Run UUID");
    }

    private static String fingerprint(String op, String owner, String project, String run,
            String node, long expected) {
        String raw = String.join("\u0000", op, owner, project, run, node, Long.toString(expected));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private record Node(String id, String nodeId, String type, String status,
                        int attempt, String handler, Timestamp updatedAt) { }
}
