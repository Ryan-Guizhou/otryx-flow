package com.peachsoft.otryx.flow.engine.dag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.DagNodeView;
import com.peachsoft.otryx.flow.contracts.v1.DagCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagRetryCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagControlReply;
import com.peachsoft.otryx.flow.contracts.v1.DagControlHistoryReply;
import com.peachsoft.otryx.flow.contracts.v1.DagRpc;
import com.peachsoft.otryx.flow.contracts.v1.DagRunDetailView;
import com.peachsoft.otryx.flow.contracts.v1.DagRunListReply;
import com.peachsoft.otryx.flow.contracts.v1.DagRunView;
import com.peachsoft.otryx.flow.contracts.v1.DagStartCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagStartReply;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
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
 * DAG Run Engine RPC：固定版本、幂等启动、租户隔离、节点状态查询。
 *
 * <p>Metadata 查询严格在 Engine DB 事务外，保障服务间不会共享事务或 JDBC。
 */
@OtryxRpcService(interfaceClass = DagRpc.class, version = "1.0.0")
public class DagRunRpcService implements DagRpc {
    private static final String CREATED = "CREATED";
    private static final String DUPLICATE = "DUPLICATE";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String CONFLICT = "CONFLICT";
    private static final String INVALID = "INVALID";
    private static final int MAX_INPUT_BYTES = 16 * 1024;
    private static final int MAX_LIST = 100;
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern REQUEST_KEY = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final DagRunCoordinator coordinator;

    @OtryxRpcReference(version = "1.0.0")
    private WorkflowRpc workflows;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DagControlService controls;

    /** 独立 Engine 本地事务；WorkflowRpc 的远程查询必须在事务开始前结束。 */
    public DagRunRpcService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            ObjectMapper mapper, DagRunCoordinator coordinator) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(txManager);
        this.transaction.setTimeout(8);
        this.mapper = mapper;
        this.coordinator = coordinator;
    }

    @Override
    public DagStartReply start(DagStartCommand command) {
        if (command == null) return new DagStartReply(INVALID, null, false);
        String owner = validOwner(command.ownerUsername());
        String project = validUuid(command.projectId());
        String workflow = validUuid(command.workflowId());
        String key = validKey(command.requestKey());
        if (command.workflowRevision() < 1 || command.inputJson() == null
                || command.inputJson().getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            return new DagStartReply(INVALID, null, false);
        }
        final String input;
        try {
            JsonNode parsed = mapper.readTree(command.inputJson());
            if (parsed == null || !parsed.isObject()) return new DagStartReply(INVALID, null, false);
            input = mapper.writeValueAsString(parsed);
        } catch (JsonProcessingException invalid) {
            return new DagStartReply(INVALID, null, false);
        }
        String digest = fingerprint(owner, project, workflow, command.workflowRevision(), input);
        DagStartReply previous = replay(owner, key, digest);
        if (previous != null) return previous;

        // The published Revision is project-scoped and immutable; no direct reads of Metadata tables.
        WorkflowRevisionView revision = workflows.revision(owner, project, workflow,
                command.workflowRevision());
        if (revision == null) return new DagStartReply(NOT_FOUND, null, false);
        final DagDependencyPlanner.Plan plan;
        try {
            plan = DagDependencyPlanner.load(revision.graphJson(), revision.graphSha256(), mapper);
        } catch (IllegalArgumentException invalid) {
            return new DagStartReply(INVALID, null, false);
        }
        try {
            return transaction.execute(ignored -> {
                String runId = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_dag_run (id, owner_username, project_id, workflow_id, "
                                + "workflow_revision, graph_sha256, graph_json, input_json, request_key, "
                                + "request_fingerprint, status, created_at, updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,'RUNNING',?,?)",
                        runId, owner, project, workflow, command.workflowRevision(),
                        revision.graphSha256(), revision.graphJson(), input, key, digest, now, now);
                JsonNode graph = readGraph(revision.graphJson());
                for (JsonNode node : graph.path("nodes")) {
                    String nodeId = node.path("id").asText();
                    DagDependencyPlanner.Node spec = plan.nodes().get(nodeId);
                    if (spec == null) throw new IllegalStateException("Published node differs from plan");
                    boolean task = spec.type().equals("TASK");
                    jdbc.update("INSERT INTO flow_dag_node_run "
                                    + "(id,dag_run_id,node_id,logical_attempt,node_type,status,condition_decision,"
                                    + "handler_key,parameters_json,timeout_seconds,max_attempts,created_at,updated_at) "
                                    + "VALUES (?,?,?,1,?,'BLOCKED',NULL,?,?,?,?,?,?)",
                            UUID.randomUUID().toString(), runId, nodeId, spec.type(),
                            task ? node.path("handlerKey").asText() : null,
                            task ? node.path("parameters").toString() : null,
                            task ? node.path("timeoutSeconds").asInt() : null,
                            task ? node.path("maxAttempts").asInt() : null, now, now);
                }
                if (coordinator.lockRunProject(runId) == null) {
                    throw new IllegalStateException("New DAG Run disappeared");
                }
                coordinator.advanceLocked(runId);
                return new DagStartReply(CREATED, find(owner, project, runId), false);
            });
        } catch (DuplicateKeyException raced) {
            DagStartReply committed = replay(owner, key, digest);
            return committed == null ? new DagStartReply(CONFLICT, null, false) : committed;
        }
    }

    @Override
    public DagRunListReply list(String ownerUsername, String projectId) {
        String owner = validOwner(ownerUsername), project = validUuid(projectId);
        List<DagRunView> runs = jdbc.query(
                "SELECT id,project_id,workflow_id,workflow_revision,graph_sha256,status,"
                        + "created_at,updated_at FROM flow_dag_run WHERE owner_username = ? "
                        + "AND project_id = ? ORDER BY created_at DESC,id DESC LIMIT ?",
                (rs, row) -> view(rs), owner, project, MAX_LIST);
        return new DagRunListReply(runs.toArray(DagRunView[]::new));
    }

    @Override
    public DagRunDetailView detail(String ownerUsername, String projectId, String runId) {
        String owner = validOwner(ownerUsername), project = validUuid(projectId), id = validUuid(runId);
        DagRunView run = find(owner, project, id);
        if (run == null) return null;
        List<DagNodeView> nodes = jdbc.query(
                "SELECT n.id,n.node_id,n.node_type,n.status,n.condition_decision,n.updated_at,n.logical_attempt "
                        + "FROM flow_dag_node_run n JOIN flow_dag_run r ON n.dag_run_id = r.id "
                        + "WHERE r.id = ? AND r.owner_username = ? AND r.project_id = ? "
                        + "ORDER BY n.node_id,n.logical_attempt",
                (rs, row) -> new DagNodeView(rs.getString("id"), rs.getString("node_id"),
                        rs.getString("node_type"), rs.getString("status"),
                        (Boolean) rs.getObject("condition_decision"),
                        rs.getTimestamp("updated_at").toInstant().toString(),
                        rs.getInt("logical_attempt")),
                id, owner, project);
        return new DagRunDetailView(run, nodes.toArray(DagNodeView[]::new));
    }

    /** 操作幂等及父 Run 行锁均由 Engine 控制面实现。 */
    @Override
    public DagControlReply cancel(DagCancelCommand command) {
        if (controls == null) throw new IllegalStateException("DAG control service is unavailable");
        return controls.cancel(command);
    }

    /** 重试采用新 NodeRun ID，不回写旧 Worker 终态。 */
    @Override
    public DagControlReply retry(DagRetryCommand command) {
        if (controls == null) throw new IllegalStateException("DAG control service is unavailable");
        return controls.retry(command);
    }

    /** 返回当前用户的取消/逻辑重试审计。 */
    @Override
    public DagControlHistoryReply history(String owner, String projectId, String runId) {
        if (controls == null) throw new IllegalStateException("DAG control service is unavailable");
        return controls.history(owner, projectId, runId);
    }

    private DagStartReply replay(String owner, String key, String fingerprint) {
        List<String[]> existing = jdbc.query(
                "SELECT request_fingerprint, project_id, id FROM flow_dag_run "
                        + "WHERE owner_username = ? AND request_key = ?",
                (rs, index) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)},
                owner, key);
        if (existing.isEmpty()) return null;
        String[] previous = existing.getFirst();
        return fingerprint.equals(previous[0])
                ? new DagStartReply(DUPLICATE, find(owner, previous[1], previous[2]), true)
                : new DagStartReply(CONFLICT, null, true);
    }

    private DagRunView find(String owner, String project, String id) {
        List<DagRunView> found = jdbc.query(
                "SELECT id,project_id,workflow_id,workflow_revision,graph_sha256,status,"
                        + "created_at,updated_at FROM flow_dag_run WHERE owner_username = ? "
                        + "AND project_id = ? AND id = ?",
                (rs, row) -> view(rs), owner, project, id);
        return found.isEmpty() ? null : found.getFirst();
    }

    private JsonNode readGraph(String graph) {
        try { return mapper.readTree(graph); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Corrupt DAG snapshot", invalid); }
    }

    private static DagRunView view(ResultSet rs) throws SQLException {
        return new DagRunView(rs.getString("id"), rs.getString("project_id"),
                rs.getString("workflow_id"), rs.getInt("workflow_revision"),
                rs.getString("graph_sha256"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(), rs.getLong("run_version"));
    }

    private static String validOwner(String value) {
        if (value == null || !OWNER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid DAG principal");
        }
        return value;
    }

    private static String validUuid(String value) {
        if (value != null) {
            try {
                String valid = UUID.fromString(value).toString();
                if (valid.equals(value)) return valid;
            } catch (IllegalArgumentException invalid) {
                // Use uniform error to avoid leaking identifiers.
            }
        }
        throw new IllegalArgumentException("Invalid DAG UUID");
    }

    private static String validKey(String value) {
        if (value == null || !REQUEST_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid DAG idempotency key");
        }
        return value;
    }

    private static String fingerprint(String owner, String project, String workflow,
            int revision, String input) {
        String material = String.join("\u0000", owner, project, workflow,
                Integer.toString(revision), input);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
