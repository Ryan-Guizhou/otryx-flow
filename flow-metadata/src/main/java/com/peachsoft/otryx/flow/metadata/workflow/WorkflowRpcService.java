package com.peachsoft.otryx.flow.metadata.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowListReply;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowPublishCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowSaveDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowView;
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

/** 工作流定义专属 Metadata Schema 的原子草稿、版本、所有权和幂等实现。 */
@OtryxRpcService(interfaceClass = WorkflowRpc.class, version = "1.0.0")
public class WorkflowRpcService implements WorkflowRpc {
    private static final String OK = "OK";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String CONFLICT = "CONFLICT";
    private static final String INVALID = "INVALID";
    private static final int MAX_LIST = 100;
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern REQUEST_KEY = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;

    /** 将写操作严格约束在 Metadata 的本地 JDBC 事务中。 */
    public WorkflowRpcService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(txManager));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public WorkflowListReply list(String ownerUsername, String projectId) {
        String owner = validOwner(ownerUsername);
        String project = validUuid(projectId);
        List<WorkflowView> result = jdbc.query(
                "SELECT w.* FROM flow_workflow_definition w "
                + "JOIN flow_project p ON p.id = w.project_id "
                + "WHERE p.owner_username = ? AND w.project_id = ? "
                + "ORDER BY w.updated_at DESC, w.id DESC LIMIT ?",
                (rs, row) -> view(rs), owner, project, MAX_LIST);
        return new WorkflowListReply(result.toArray(WorkflowView[]::new));
    }

    @Override
    public WorkflowMutationReply createDraft(WorkflowCreateDraftCommand command) {
        Objects.requireNonNull(command, "command");
        String owner = validOwner(command.ownerUsername());
        String project = validUuid(command.projectId());
        String key = validKey(command.requestKey());
        final Draft draft;
        try {
            draft = validate(command.name(), command.graphJson());
        } catch (IllegalArgumentException invalid) {
            return reply(INVALID, null, false);
        }
        String fingerprint = fingerprint(owner, project, draft);
        try {
            return transaction.execute(ignored -> {
                if (!ownsProject(owner, project)) return reply(NOT_FOUND, null, false);
                String id = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_workflow_definition "
                                + "(id, project_id, owner_username, create_request_key, create_fingerprint, "
                                + "name, graph_json, draft_version, published_revision, "
                                + "last_published_draft_version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, 0, 0, ?, ?)",
                        id, project, owner, key, fingerprint, draft.name(), draft.graphJson(), now, now);
                return reply(OK, find(owner, project, id, false), false);
            });
        } catch (DuplicateKeyException duplicate) {
            List<WorkflowView> existing = jdbc.query(
                    "SELECT * FROM flow_workflow_definition WHERE owner_username = ? AND create_request_key = ?",
                    (rs, row) -> view(rs), owner, key);
            if (existing.isEmpty()) throw duplicate;
            WorkflowView previous = existing.getFirst();
            String stored = jdbc.queryForObject(
                    "SELECT create_fingerprint FROM flow_workflow_definition WHERE id = ?",
                    String.class, previous.id());
            if (!project.equals(previous.projectId()) || !fingerprint.equals(stored)) {
                return reply(CONFLICT, previous, true);
            }
            return reply(OK, previous, true);
        }
    }

    @Override
    public WorkflowMutationReply saveDraft(WorkflowSaveDraftCommand command) {
        Objects.requireNonNull(command, "command");
        String owner = validOwner(command.ownerUsername());
        String project = validUuid(command.projectId());
        String id = validUuid(command.workflowId());
        if (command.expectedDraftVersion() < 1) return reply(INVALID, null, false);
        final Draft draft;
        try {
            draft = validate(command.name(), command.graphJson());
        } catch (IllegalArgumentException invalid) {
            return reply(INVALID, null, false);
        }
        return transaction.execute(ignored -> {
            WorkflowView current = find(owner, project, id, true);
            if (current == null) return reply(NOT_FOUND, null, false);
            if (current.draftVersion() != command.expectedDraftVersion()) {
                return reply(CONFLICT, current, false);
            }
            int changed = jdbc.update(
                    "UPDATE flow_workflow_definition SET name = ?, graph_json = ?, "
                    + "draft_version = draft_version + 1, updated_at = ? "
                    + "WHERE id = ? AND draft_version = ?",
                    draft.name(), draft.graphJson(), Timestamp.from(Instant.now()),
                    id, command.expectedDraftVersion());
            if (changed != 1) return reply(CONFLICT, current, false);
            return reply(OK, find(owner, project, id, false), false);
        });
    }

    @Override
    public WorkflowMutationReply publish(WorkflowPublishCommand command) {
        Objects.requireNonNull(command, "command");
        String owner = validOwner(command.ownerUsername());
        String project = validUuid(command.projectId());
        String id = validUuid(command.workflowId());
        String key = validKey(command.requestKey());
        if (command.expectedDraftVersion() < 1) return reply(INVALID, null, false);
        return transaction.execute(ignored -> {
            WorkflowView current = find(owner, project, id, true);
            if (current == null) return reply(NOT_FOUND, null, false);
            List<Long> prior = jdbc.query(
                    "SELECT source_draft_version FROM flow_workflow_revision "
                    + "WHERE workflow_id = ? AND publish_request_key = ?",
                    (rs, row) -> rs.getLong(1), id, key);
            if (!prior.isEmpty()) {
                return prior.getFirst() == command.expectedDraftVersion()
                        ? reply(OK, current, true) : reply(CONFLICT, current, true);
            }
            if (current.draftVersion() != command.expectedDraftVersion()
                    || current.lastPublishedDraftVersion() >= current.draftVersion()) {
                return reply(CONFLICT, current, false);
            }
            // 防止脏数据库快照绕过发布前的后端安全校验。
            WorkflowDefinitionGraph.Validated validated;
            try {
                validated = WorkflowDefinitionGraph.validate(mapper, current.graphJson());
            } catch (IllegalArgumentException invalid) {
                return reply(INVALID, current, false);
            }
            int revision = Math.addExact(current.publishedRevision(), 1);
            jdbc.update("INSERT INTO flow_workflow_revision "
                            + "(workflow_id, revision, publish_request_key, name, graph_json, "
                            + "graph_sha256, source_draft_version, published_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, revision, key, current.name(), validated.canonicalJson(),
                    validated.sha256(), current.draftVersion(), Timestamp.from(Instant.now()));
            jdbc.update("UPDATE flow_workflow_definition SET published_revision = ?, "
                            + "last_published_draft_version = ?, updated_at = ? WHERE id = ?",
                    revision, current.draftVersion(), Timestamp.from(Instant.now()), id);
            return reply(OK, find(owner, project, id, false), false);
        });
    }

    @Override
    public WorkflowRevisionView revision(String ownerUsername, String projectId,
            String workflowId, int number) {
        String owner = validOwner(ownerUsername);
        String project = validUuid(projectId);
        String id = validUuid(workflowId);
        if (number < 1) throw new IllegalArgumentException("Invalid workflow revision");
        List<WorkflowRevisionView> found = jdbc.query(
                "SELECT r.* FROM flow_workflow_revision r "
                + "JOIN flow_workflow_definition w ON w.id = r.workflow_id "
                + "JOIN flow_project p ON p.id = w.project_id "
                + "WHERE r.workflow_id = ? AND w.project_id = ? "
                + "AND p.owner_username = ? AND r.revision = ?",
                (rs, row) -> new WorkflowRevisionView(rs.getString("workflow_id"),
                        rs.getInt("revision"), rs.getString("name"), rs.getString("graph_json"),
                        rs.getString("graph_sha256"), rs.getLong("source_draft_version"),
                        rs.getTimestamp("published_at").toInstant().toString()),
                id, project, owner, number);
        return found.isEmpty() ? null : found.getFirst();
    }

    private boolean ownsProject(String owner, String project) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_project WHERE id = ? AND owner_username = ?",
                Integer.class, project, owner);
        return count != null && count == 1;
    }

    private WorkflowView find(String owner, String project, String id, boolean lock) {
        List<WorkflowView> values = jdbc.query(
                "SELECT w.* FROM flow_workflow_definition w "
                + "JOIN flow_project p ON p.id = w.project_id "
                + "WHERE w.id = ? AND w.project_id = ? AND p.owner_username = ?"
                + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> view(rs), id, project, owner);
        return values.isEmpty() ? null : values.getFirst();
    }

    private Draft validate(String name, String json) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new IllegalArgumentException("Invalid workflow name");
        }
        return new Draft(name.strip(), WorkflowDefinitionGraph.validate(mapper, json).canonicalJson());
    }

    private static WorkflowView view(ResultSet rs) throws SQLException {
        return new WorkflowView(rs.getString("id"), rs.getString("project_id"),
                rs.getString("name"), rs.getString("graph_json"),
                rs.getLong("draft_version"), rs.getInt("published_revision"),
                rs.getLong("last_published_draft_version"),
                rs.getTimestamp("updated_at").toInstant().toString());
    }

    private static WorkflowMutationReply reply(String code, WorkflowView data, boolean duplicate) {
        return new WorkflowMutationReply(code, data, duplicate);
    }

    private static String validOwner(String input) {
        if (input == null || !OWNER.matcher(input).matches()) {
            throw new IllegalArgumentException("Invalid owner");
        }
        return input;
    }

    private static String validUuid(String input) {
        if (input == null) throw new IllegalArgumentException("Missing UUID");
        try {
            String canonical = UUID.fromString(input).toString();
            if (canonical.equals(input)) return canonical;
        } catch (IllegalArgumentException invalid) {
            // no-op; a stable validation error follows.
        }
        throw new IllegalArgumentException("Invalid UUID");
    }

    private static String validKey(String input) {
        if (input == null || !REQUEST_KEY.matcher(input).matches()) {
            throw new IllegalArgumentException("Invalid request key");
        }
        return input;
    }

    private static String fingerprint(String owner, String project, Draft draft) {
        try {
            String payload = String.join(String.valueOf((char) 0),
                    owner, project, draft.name(), draft.graphJson());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 not installed", unavailable);
        }
    }

    private record Draft(String name, String graphJson) { }
}

