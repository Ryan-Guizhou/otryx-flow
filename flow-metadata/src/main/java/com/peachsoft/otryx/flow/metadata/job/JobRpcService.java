package com.peachsoft.otryx.flow.metadata.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.JobCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobDraftUpdateCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobListReply;
import com.peachsoft.otryx.flow.contracts.v1.JobMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.JobPublishCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobView;
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
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persisted job drafts and immutable publication revisions with scoped ownership. */
@OtryxRpcService(interfaceClass = JobRpc.class, version = "1.0.0")
public class JobRpcService implements JobRpc {
    private static final String OK = "OK";
    private static final String CONFLICT = "CONFLICT";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final Pattern PRINCIPAL = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern REQUEST_KEY = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private static final Pattern HANDLER = Pattern.compile("[a-z][a-z0-9_.-]{2,127}");
    private static final int MAX_NAME = 100;
    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final int MAX_LIST = 100;
    private static final int MAX_TIMEOUT_SECONDS = 86_400;
    private static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;

    /** Construct the stateful RPC adapter; transaction ownership remains in metadata. */
    public JobRpcService(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(txManager));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public JobListReply list(String ownerUsername, String projectId) {
        String owner = owner(ownerUsername);
        String project = identifier(projectId);
        List<JobView> jobs = jdbc.query("SELECT j.* FROM flow_job_definition j "
                + "JOIN flow_project p ON p.id = j.project_id "
                + "WHERE p.owner_username = ? AND j.project_id = ? "
                + "ORDER BY j.updated_at DESC, j.id DESC LIMIT ?",
                (rs, row) -> readJob(rs), owner, project, MAX_LIST);
        return new JobListReply(jobs.toArray(JobView[]::new));
    }

    @Override
    public JobMutationReply createDraft(JobCreateDraftCommand command) {
        Objects.requireNonNull(command, "command");
        String owner = owner(command.ownerUsername());
        String project = identifier(command.projectId());
        String key = requestKey(command.requestKey());
        Draft draft = validate(command.name(), command.handlerKey(), command.parametersJson(),
                command.timeoutSeconds(), command.maxAttempts());
        String digest = fingerprint(owner, project, draft);
        try {
            return transaction.execute(ignored -> {
                if (!ownsProject(owner, project)) return reply(NOT_FOUND, null, false);
                String id = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_job_definition "
                                + "(id, project_id, owner_username, create_request_key, create_fingerprint, "
                                + "name, handler_key, parameters_json, timeout_seconds, max_attempts, "
                                + "draft_version, published_revision, last_published_draft_version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 0, ?, ?)",
                        id, project, owner, key, digest, draft.name(), draft.handler(), draft.json(),
                        draft.timeoutSeconds(), draft.maxAttempts(), now, now);
                return reply(OK, getJob(owner, project, id, false), false);
            });
        } catch (DuplicateKeyException duplicate) {
            List<JobView> previous = jdbc.query("SELECT * FROM flow_job_definition "
                            + "WHERE owner_username = ? AND create_request_key = ?",
                    (rs, row) -> readJob(rs), owner, key);
            if (previous.isEmpty()) throw duplicate;
            JobView job = previous.getFirst();
            String storedDigest = jdbc.queryForObject("SELECT create_fingerprint FROM flow_job_definition "
                    + "WHERE id = ?", String.class, job.id());
            if (!job.projectId().equals(project) || !digest.equals(storedDigest)) {
                return reply(CONFLICT, job, true);
            }
            return reply(OK, job, true);
        }
    }

    @Override
    public JobMutationReply saveDraft(JobDraftUpdateCommand command) {
        Objects.requireNonNull(command, "command");
        String owner = owner(command.ownerUsername());
        String project = identifier(command.projectId());
        String jobId = identifier(command.jobId());
        if (command.expectedDraftVersion() < 1) throw new IllegalArgumentException("Invalid draft version");
        Draft draft = validate(command.name(), command.handlerKey(), command.parametersJson(),
                command.timeoutSeconds(), command.maxAttempts());
        return transaction.execute(ignored -> {
            JobView current = getJob(owner, project, jobId, true);
            if (current == null) return reply(NOT_FOUND, null, false);
            if (current.draftVersion() != command.expectedDraftVersion()) {
                return reply(CONFLICT, current, false);
            }
            jdbc.update("UPDATE flow_job_definition SET name = ?, handler_key = ?, parameters_json = ?, "
                            + "timeout_seconds = ?, max_attempts = ?, draft_version = draft_version + 1, "
                            + "updated_at = ? WHERE id = ? AND draft_version = ?",
                    draft.name(), draft.handler(), draft.json(), draft.timeoutSeconds(), draft.maxAttempts(),
                    Timestamp.from(Instant.now()), jobId, command.expectedDraftVersion());
            return reply(OK, getJob(owner, project, jobId, false), false);
        });
    }

    @Override
    public JobMutationReply publish(JobPublishCommand command) {
        Objects.requireNonNull(command, "command");
        String owner = owner(command.ownerUsername());
        String project = identifier(command.projectId());
        String jobId = identifier(command.jobId());
        String key = requestKey(command.requestKey());
        if (command.expectedDraftVersion() < 1) throw new IllegalArgumentException("Invalid draft version");
        return transaction.execute(ignored -> {
            JobView current = getJob(owner, project, jobId, true);
            if (current == null) return reply(NOT_FOUND, null, false);
            Integer prior = jdbc.query("SELECT revision FROM flow_job_revision "
                            + "WHERE job_id = ? AND publish_request_key = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, jobId, key);
            if (prior != null) return reply(OK, current, true);
            if (current.draftVersion() != command.expectedDraftVersion()
                    || current.lastPublishedDraftVersion() >= current.draftVersion()) {
                return reply(CONFLICT, current, false);
            }
            int next = Math.addExact(current.publishedRevision(), 1);
            jdbc.update("INSERT INTO flow_job_revision "
                            + "(job_id, revision, publish_request_key, name, handler_key, parameters_json, "
                            + "timeout_seconds, max_attempts, source_draft_version, published_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    jobId, next, key, current.name(), current.handlerKey(), current.parametersJson(),
                    current.timeoutSeconds(), current.maxAttempts(), current.draftVersion(),
                    Timestamp.from(Instant.now()));
            jdbc.update("UPDATE flow_job_definition SET published_revision = ?, "
                            + "last_published_draft_version = ?, updated_at = ? WHERE id = ?",
                    next, current.draftVersion(), Timestamp.from(Instant.now()), jobId);
            return reply(OK, getJob(owner, project, jobId, false), false);
        });
    }

    @Override
    public JobRevisionView revision(String ownerUsername, String projectId, String jobId, int number) {
        String owner = owner(ownerUsername);
        String project = identifier(projectId);
        String job = identifier(jobId);
        if (number <= 0) throw new IllegalArgumentException("Invalid revision number");
        List<JobRevisionView> revisions = jdbc.query("SELECT r.* FROM flow_job_revision r "
                        + "JOIN flow_job_definition j ON j.id = r.job_id "
                        + "JOIN flow_project p ON p.id = j.project_id "
                        + "WHERE j.id = ? AND j.project_id = ? AND p.owner_username = ? AND r.revision = ?",
                (rs, row) -> new JobRevisionView(rs.getString("job_id"), rs.getInt("revision"),
                        rs.getString("name"), rs.getString("handler_key"),
                        rs.getString("parameters_json"), rs.getInt("timeout_seconds"),
                        rs.getInt("max_attempts"), rs.getLong("source_draft_version"),
                        rs.getTimestamp("published_at").toInstant().toString()),
                job, project, owner, number);
        return revisions.isEmpty() ? null : revisions.getFirst();
    }

    private boolean ownsProject(String owner, String project) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM flow_project "
                + "WHERE id = ? AND owner_username = ?", Integer.class, project, owner);
        return count != null && count == 1;
    }

    private JobView getJob(String owner, String project, String id, boolean lock) {
        String sql = "SELECT j.* FROM flow_job_definition j "
                + "JOIN flow_project p ON p.id = j.project_id "
                + "WHERE j.id = ? AND j.project_id = ? AND p.owner_username = ?"
                + (lock ? " FOR UPDATE" : "");
        List<JobView> values = jdbc.query(sql, (rs, row) -> readJob(rs), id, project, owner);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static JobMutationReply reply(String code, JobView job, boolean duplicate) {
        return new JobMutationReply(code, job, duplicate);
    }

    private static JobView readJob(ResultSet rs) throws SQLException {
        return new JobView(rs.getString("id"), rs.getString("project_id"), rs.getString("name"),
                rs.getString("handler_key"), rs.getString("parameters_json"),
                rs.getInt("timeout_seconds"), rs.getInt("max_attempts"),
                rs.getLong("draft_version"), rs.getInt("published_revision"),
                rs.getLong("last_published_draft_version"),
                rs.getTimestamp("updated_at").toInstant().toString());
    }

    private Draft validate(String name, String handler, String json, int timeout, int maxAttempts) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
            throw new IllegalArgumentException("Invalid job name");
        }
        if (handler == null || !HANDLER.matcher(handler).matches()) {
            throw new IllegalArgumentException("Invalid handler key");
        }
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Invalid parameters size");
        }
        try {
            JsonNode parsed = mapper.readTree(json);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("Parameters must be JSON object");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid JSON parameters");
        }
        if (timeout < 1 || timeout > MAX_TIMEOUT_SECONDS || maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid execution policy");
        }
        return new Draft(name.strip(), handler, json, timeout, maxAttempts);
    }

    private static String owner(String value) {
        if (value == null || !PRINCIPAL.matcher(value).matches()) throw new IllegalArgumentException("Invalid owner");
        return value;
    }

    private static String identifier(String value) {
        if (value == null) throw new IllegalArgumentException("Missing identifier");
        try { return UUID.fromString(value).toString(); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid identifier"); }
    }

    private static String requestKey(String value) {
        if (value == null || !REQUEST_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid request key");
        }
        return value;
    }

    private static String fingerprint(String owner, String project, Draft draft) {
        try {
            String payload = String.join(String.valueOf((char) 0), owner, project, draft.name(), draft.handler(),
                    draft.json(), Integer.toString(draft.timeoutSeconds()), Integer.toString(draft.maxAttempts()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not installed", unavailable);
        }
    }

    private record Draft(String name, String handler, String json, int timeoutSeconds, int maxAttempts) { }
}
