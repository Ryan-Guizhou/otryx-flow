package com.peachsoft.otryx.flow.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.CancelJobRunCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobRunListReply;
import com.peachsoft.otryx.flow.contracts.v1.JobRunMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.JobRunRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobRunView;
import com.peachsoft.otryx.flow.contracts.v1.StartManualJobRunCommand;
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
 * 手动执行和停止普通 Job。Cron 火点仍由 EngineRpcService 接收。
 *
 * <p>请求键存在独立表中。手动运行不写入虚假的 schedule 标识。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@OtryxRpcService(interfaceClass = JobRunRpc.class, version = "1.0.0")
public class JobRunRpcService implements JobRunRpc {
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern KEY = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private static final int MAX_RUNS = 100;
    private static final String WAITING = "WAITING_DISPATCH";
    private static final int MAX_OVERRIDE_BYTES = 16 * 1024;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /** 事务只覆盖 Engine 自己的表。 */
    public JobRunRpcService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setTimeout(8);
    }

    @Override
    public JobRunMutationReply startManual(StartManualJobRunCommand command) {
        String owner = owner(command.ownerUsername());
        String project = uuid(command.projectId());
        String job = uuid(command.jobId());
        String key = requestKey(command.requestKey());
        if (command.jobRevision() < 1) throw new IllegalArgumentException("Invalid job revision");
        String retry = command.retryOfRunId() == null || command.retryOfRunId().isBlank()
                ? null : uuid(command.retryOfRunId());
        String override;
        if (retry == null) {
            override = normalizeOverride(command.parameterOverrideJson());
        } else {
            List<RetrySource> sources = jdbc.query("SELECT job_id, job_revision, status, parameter_override "
                            + "FROM flow_workflow_run WHERE id=? AND owner_username=? AND project_id=?",
                    (rs, index) -> new RetrySource(rs.getString("job_id"), rs.getInt("job_revision"),
                            rs.getString("status"), rs.getString("parameter_override")),
                    retry, owner, project);
            if (sources.isEmpty()) return new JobRunMutationReply("NOT_FOUND", null, null, false);
            RetrySource source = sources.getFirst();
            if (!job.equals(source.jobId()) || command.jobRevision() != source.jobRevision()
                    || !("FAILED".equals(source.status()) || "CANCELLED".equals(source.status())
                    || "SUCCEEDED".equals(source.status()))) {
                return new JobRunMutationReply("CONFLICT", retry, source.status(), false);
            }
            override = normalizeOverride(source.parameterOverride());
        }
        String origin = retry == null ? "MANUAL" : "RETRY";
        String fingerprint = fingerprint(owner, project, job, command.jobRevision(), key, override, retry);
        JobRunMutationReply previous = replay(owner, key, fingerprint);
        if (previous != null) return previous;
        try {
            return tx.execute(ignored -> {
                String eventId = UUID.randomUUID().toString();
                String runId = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_engine_inbox (event_id, payload_fingerprint, run_id, received_at) "
                        + "VALUES (?,?,?,?)", eventId, fingerprint, runId, now);
                jdbc.update("INSERT INTO flow_workflow_run (id, event_id, owner_username, project_id, "
                                + "schedule_id, schedule_revision, job_id, job_revision, planned_fire_at_utc, "
                                + "origin, status, control_state, run_version, failure_code, retry_of_run_id, "
                                + "parameter_override, created_at, updated_at) "
                                + "VALUES (?,?,?,?,NULL,NULL,?,?,NULL,?,?,'NONE',1,NULL,?,?,?,?)",
                        runId, eventId, owner, project, job, command.jobRevision(), origin, WAITING,
                        retry, override, now, now);
                jdbc.update("INSERT INTO flow_engine_dispatch_outbox "
                                + "(run_id, event_id, delivery_state, attempts, created_at) "
                                + "VALUES (?,?, 'PENDING',0,?)",
                        runId, eventId, now);
                jdbc.update("INSERT INTO flow_job_run_request (owner_username, request_key, fingerprint, run_id, "
                        + "created_at) VALUES (?,?,?,?,?)", owner, key, fingerprint, runId, now);
                return new JobRunMutationReply("CREATED", runId, WAITING, false);
            });
        } catch (DuplicateKeyException duplicate) {
            JobRunMutationReply committed = replay(owner, key, fingerprint);
            return committed == null ? new JobRunMutationReply("CONFLICT", null, null, false) : committed;
        }
    }

    @Override
    public JobRunListReply list(String ownerUsername, String projectId) {
        List<JobRunView> rows = jdbc.query("SELECT * FROM flow_workflow_run WHERE owner_username=? "
                        + "AND project_id=? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, index) -> read(rs), owner(ownerUsername), uuid(projectId), MAX_RUNS);
        return new JobRunListReply(rows.toArray(JobRunView[]::new));
    }

    @Override
    public JobRunView detail(String ownerUsername, String projectId, String runId) {
        List<JobRunView> rows = jdbc.query("SELECT * FROM flow_workflow_run WHERE id=? AND owner_username=? "
                        + "AND project_id=?",
                (rs, index) -> read(rs), uuid(runId), owner(ownerUsername), uuid(projectId));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public JobRunMutationReply cancel(CancelJobRunCommand command) {
        String owner = owner(command.ownerUsername());
        String project = uuid(command.projectId());
        String run = uuid(command.runId());
        String key = requestKey(command.requestKey());
        return tx.execute(ignored -> {
            List<String[]> rows = jdbc.query("SELECT status, run_version, owner_username, project_id "
                            + "FROM flow_workflow_run WHERE id=? FOR UPDATE",
                    (rs, index) -> new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                    run);
            if (rows.isEmpty() || !owner.equals(rows.getFirst()[2]) || !project.equals(rows.getFirst()[3])) {
                return new JobRunMutationReply("NOT_FOUND", null, null, false);
            }
            String status = rows.getFirst()[0];
            long version = Long.parseLong(rows.getFirst()[1]);
            if ("SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return new JobRunMutationReply("TERMINAL", run, status, false);
            }
            if (command.expectedRunVersion() > 0 && command.expectedRunVersion() != version) {
                return new JobRunMutationReply("CONFLICT", run, status, false);
            }
            if ("CANCEL_REQUESTED".equals(status)) {
                return new JobRunMutationReply("DUPLICATE", run, status, true);
            }
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("UPDATE flow_workflow_run SET status='CANCEL_REQUESTED', control_state='CANCEL_REQUESTED', "
                    + "run_version=run_version+1, updated_at=? WHERE id=?", now, run);
            Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM flow_engine_run_cancel_outbox WHERE run_id=?",
                    Integer.class, run);
            if (existing == null || existing == 0) {
                jdbc.update("INSERT INTO flow_engine_run_cancel_outbox (run_id, event_id, owner_username, "
                                + "project_id, delivery_state, attempts, next_attempt_at, created_at) "
                                + "VALUES (?,?,?,?,'PENDING',0,?,?)",
                        run, UUID.randomUUID().toString(), owner, project, now, now);
            }
            return new JobRunMutationReply("CANCEL_REQUESTED", run, "CANCEL_REQUESTED", false);
        });
    }

    private JobRunMutationReply replay(String owner, String key, String fingerprint) {
        List<String[]> rows = jdbc.query("SELECT fingerprint, run_id FROM flow_job_run_request "
                        + "WHERE owner_username=? AND request_key=?",
                (rs, index) -> new String[]{rs.getString(1), rs.getString(2)}, owner, key);
        if (rows.isEmpty()) return null;
        if (!fingerprint.equals(rows.getFirst()[0])) return new JobRunMutationReply("CONFLICT", null, null, true);
        String status = jdbc.queryForObject("SELECT status FROM flow_workflow_run WHERE id=?", String.class,
                rows.getFirst()[1]);
        return new JobRunMutationReply("DUPLICATE", rows.getFirst()[1], status, true);
    }

    private static JobRunView read(ResultSet rs) throws SQLException {
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new JobRunView(rs.getString("id"), rs.getString("project_id"), rs.getString("job_id"),
                rs.getInt("job_revision"), rs.getString("origin"), rs.getString("status"),
                rs.getString("control_state"), rs.getLong("run_version"), rs.getString("schedule_id"),
                rs.getString("retry_of_run_id"), rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant().toString(),
                started == null ? null : started.toInstant().toString(),
                finished == null ? null : finished.toInstant().toString());
    }

    private static String normalizeOverride(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_OVERRIDE_BYTES) {
            throw new IllegalArgumentException("Invalid parameter override");
        }
        try {
            JsonNode value = JSON_MAPPER.readTree(json);
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("Parameter override must be a JSON object");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid parameter override JSON", invalid);
        }
    }

    private record RetrySource(String jobId, int jobRevision, String status, String parameterOverride) { }

    private static String fingerprint(String owner, String project, String job, int revision, String key,
            String override, String retry) {
        String raw = String.join("\u0000", owner, project, job, Integer.toString(revision), key,
                override == null ? "" : override, retry == null ? "" : retry);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static String owner(String value) {
        if (value == null || !OWNER.matcher(value).matches()) throw new IllegalArgumentException("Invalid owner");
        return value;
    }

    private static String uuid(String value) {
        if (value == null) throw new IllegalArgumentException("Missing identifier");
        String normalized = UUID.fromString(value).toString();
        if (!normalized.equals(value)) throw new IllegalArgumentException("Noncanonical identifier");
        return normalized;
    }

    private static String requestKey(String value) {
        if (value == null || !KEY.matcher(value).matches()) throw new IllegalArgumentException("Invalid request key");
        return value;
    }
}
