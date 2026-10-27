package com.peachsoft.otryx.flow.engine;

import com.peachsoft.otryx.flow.contracts.v1.EngineFireReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineRpc;
import com.peachsoft.otryx.flow.contracts.v1.EngineRunListReply;
import com.peachsoft.otryx.flow.contracts.v1.EngineRunView;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeReply;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleFireCommand;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 独立 Engine RPC Provider：Inbox 与逻辑 Run 必须由同一数据库事务提交。
 * 同一 eventId 可任意重投，但内容不一致或逻辑火点重复且 ID 不同必须拒绝。
 */
@OtryxRpcService(interfaceClass = EngineRpc.class, version = "1.0.0")
public class EngineRpcService implements EngineRpc {
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final int MAX_RUNS = 100;
    private static final String WAITING_DISPATCH = "WAITING_DISPATCH";
    private static final String CREATED = "CREATED";
    private static final String DUPLICATE = "DUPLICATE";
    private static final String CONFLICT = "CONFLICT";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /** 数据事务管理器必须属于 Engine 私有 DataSource。 */
    public EngineRpcService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(8);
    }

    @Override
    public EngineFireReply acceptScheduledFire(ScheduleFireCommand input) {
        Event event = validate(input);
        EngineFireReply previous = replay(event);
        if (previous != null) return previous;
        try {
            return tx.execute(ignored -> {
                String runId = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_engine_inbox "
                                + "(event_id, payload_fingerprint, run_id, received_at) VALUES (?,?,?,?)",
                        event.id(), event.fingerprint(), runId, now);
                jdbc.update("INSERT INTO flow_workflow_run "
                                + "(id, event_id, owner_username, project_id, schedule_id, schedule_revision, "
                                + "job_id, job_revision, planned_fire_at_utc, origin, status, created_at, updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        runId, event.id(), event.owner(), event.project(), event.schedule(),
                        event.scheduleRevision(), event.job(), event.jobRevision(),
                        Timestamp.from(event.planned()), event.origin(), WAITING_DISPATCH, now, now);
                jdbc.update("INSERT INTO flow_engine_dispatch_outbox "
                                + "(run_id, event_id, delivery_state, attempts, created_at) "
                                + "VALUES (?, ?, 'PENDING', 0, ?)", runId, event.id(), now);
                return new EngineFireReply(CREATED, runId, WAITING_DISPATCH);
            });
        } catch (DuplicateKeyException duplicate) {
            // Concurrent requests may both observe missing Inbox. The SQL UNIQUE constraint arbitrates.
            EngineFireReply committed = replay(event);
            return committed == null ? new EngineFireReply(CONFLICT, null, null) : committed;
        }
    }

    @Override
    public EngineRunListReply listRuns(String ownerUsername, String projectId) {
        String owner = validateOwner(ownerUsername);
        String project = validateUuid(projectId);
        List<EngineRunView> runs = jdbc.query("SELECT * FROM flow_workflow_run "
                        + "WHERE owner_username = ? AND project_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, index) -> toView(rs), owner, project, MAX_RUNS);
        return new EngineRunListReply(runs.toArray(EngineRunView[]::new));
    }


    /**
     * 接收可信 Dispatcher 的终态事件，完整载荷 SHA256 和数据库唯一索引共同仲裁重投。
     * 同一 Run 不允许被第二种终态或者第二个 eventId 覆盖。
     *
     * @param command 最终 Worker Attempt 的不可变事件
     * @return ACCEPTED、DUPLICATE 或 CONFLICT
     */
    @Override
    public EngineOutcomeReply acknowledgeTaskOutcome(EngineOutcomeCommand command) {
        if (command == null || command.leaseEpoch() < 1
                || !("SUCCEEDED".equals(command.outcome()) || "FAILED".equals(command.outcome()))) {
            throw new IllegalArgumentException("Invalid Engine task outcome");
        }
        String event = validateUuid(command.eventId());
        String run = validateUuid(command.runId());
        String task = validateUuid(command.taskId());
        String project = validateUuid(command.projectId());
        String fingerprint = outcomeFingerprint(event, run, task, project,
                command.leaseEpoch(), command.outcome());

        try {
            return tx.execute(ignored -> {
                // A row lock on the Run serializes all terminal attempts and replayed event IDs.
                List<String[]> runs = jdbc.query("SELECT project_id, status FROM flow_workflow_run "
                                + "WHERE id = ? FOR UPDATE",
                        (rs, row) -> new String[]{rs.getString("project_id"), rs.getString("status")}, run);
                if (runs.isEmpty() || !project.equals(runs.getFirst()[0])) {
                    return new EngineOutcomeReply("CONFLICT", null, null);
                }
                List<String[]> prior = jdbc.query("SELECT event_id, payload_fingerprint, outcome "
                                + "FROM flow_engine_outcome_inbox WHERE engine_run_id = ?",
                        (rs, row) -> new String[]{rs.getString("event_id"),
                                rs.getString("payload_fingerprint"), rs.getString("outcome")}, run);
                if (!prior.isEmpty()) {
                    String[] previous = prior.getFirst();
                    boolean same = event.equals(previous[0]) && fingerprint.equals(previous[1])
                            && command.outcome().equals(previous[2]);
                    return same ? new EngineOutcomeReply("DUPLICATE", run, previous[2])
                            : new EngineOutcomeReply("CONFLICT", null, null);
                }
                String status = runs.getFirst()[1];
                if (!("WAITING_DISPATCH".equals(status) || "DISPATCHED".equals(status))) {
                    return new EngineOutcomeReply("CONFLICT", null, null);
                }
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_engine_outcome_inbox "
                                + "(event_id,engine_run_id,task_id,project_id,lease_epoch,outcome,"
                                + "payload_fingerprint,accepted_at) VALUES (?,?,?,?,?,?,?,?)",
                        event, run, task, project, command.leaseEpoch(), command.outcome(), fingerprint, now);
                int changed = jdbc.update("UPDATE flow_workflow_run SET status = ?, updated_at = ? "
                                + "WHERE id = ? AND status IN ('WAITING_DISPATCH','DISPATCHED')",
                        command.outcome(), now, run);
                if (changed != 1) throw new IllegalStateException("Engine terminal outcome CAS lost");
                return new EngineOutcomeReply("ACCEPTED", run, command.outcome());
            });
        } catch (DuplicateKeyException race) {
            List<String[]> previous = jdbc.query(
                    "SELECT payload_fingerprint, outcome FROM flow_engine_outcome_inbox "
                            + "WHERE engine_run_id = ? AND event_id = ?",
                    (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, run, event);
            return !previous.isEmpty() && fingerprint.equals(previous.getFirst()[0])
                    ? new EngineOutcomeReply("DUPLICATE", run, previous.getFirst()[1])
                    : new EngineOutcomeReply("CONFLICT", null, null);
        }
    }

    private static String outcomeFingerprint(String event, String run, String task, String project,
                                             long epoch, String outcome) {
        String canonical = String.join("\u0000", event, run, task, project,
                Long.toString(epoch), outcome);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private EngineFireReply replay(Event event) {
        List<EngineFireReply> results = jdbc.query("SELECT i.payload_fingerprint, i.run_id, r.status "
                        + "FROM flow_engine_inbox i "
                        + "JOIN flow_workflow_run r ON r.id = i.run_id "
                        + "WHERE i.event_id = ?",
                (rs, row) -> event.fingerprint().equals(rs.getString(1))
                        ? new EngineFireReply(DUPLICATE, rs.getString(2), rs.getString(3))
                        : new EngineFireReply(CONFLICT, null, null), event.id());
        return results.isEmpty() ? null : results.getFirst();
    }

    private static EngineRunView toView(ResultSet rs) throws SQLException {
        return new EngineRunView(rs.getString("id"), rs.getString("event_id"),
                rs.getString("project_id"), rs.getString("schedule_id"),
                rs.getInt("schedule_revision"), rs.getString("job_id"),
                rs.getInt("job_revision"), rs.getTimestamp("planned_fire_at_utc").toInstant().toString(),
                rs.getString("origin"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant().toString());
    }

    private static Event validate(ScheduleFireCommand input) {
        if (input == null || input.scheduleRevision() < 1 || input.jobRevision() < 1
                || !"CRON".equals(input.origin())) {
            throw new IllegalArgumentException("Invalid schedule fire fields");
        }
        String eventId = validateUuid(input.eventId());
        String owner = validateOwner(input.ownerUsername());
        String project = validateUuid(input.projectId());
        String schedule = validateUuid(input.scheduleId());
        String job = validateUuid(input.jobId());
        final Instant planned;
        try { planned = Instant.parse(input.plannedFireUtc()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid fire UTC instant"); }
        String canonical = String.join("\u0000", eventId, owner, project, schedule,
                Integer.toString(input.scheduleRevision()), job, Integer.toString(input.jobRevision()),
                planned.toString(), input.origin());
        try {
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
            return new Event(eventId, owner, project, schedule, input.scheduleRevision(),
                    job, input.jobRevision(), planned, input.origin(), fingerprint);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String validateOwner(String input) {
        if (input == null || !OWNER.matcher(input).matches()) {
            throw new IllegalArgumentException("Invalid Engine owner");
        }
        return input;
    }

    private static String validateUuid(String input) {
        if (input == null) throw new IllegalArgumentException("Missing Engine identifier");
        try {
            String normalized = UUID.fromString(input).toString();
            if (!normalized.equals(input)) throw new IllegalArgumentException("Noncanonical identifier");
            return normalized;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Engine identifier");
        }
    }

    private record Event(String id, String owner, String project, String schedule,
                         int scheduleRevision, String job, int jobRevision, Instant planned,
                         String origin, String fingerprint) { }
}
