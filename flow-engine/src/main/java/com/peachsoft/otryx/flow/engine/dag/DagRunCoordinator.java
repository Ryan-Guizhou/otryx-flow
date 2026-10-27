package com.peachsoft.otryx.flow.engine.dag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Engine DAG 事务推进器：上层开启本地数据库事务后，始终先锁 WorkflowRun。
 *
 * <p>同一个 Run 的多个终态事件通过行锁顺序执行。节点变为 READY 与写入
 * 独立节点 Outbox 必须在同一事务完成；不能在事务中调用其他 RPC 服务。
 */
@Component
public class DagRunCoordinator {
    private static final int MAX_ACTIVE_NODES = 8;
    private static final String RUNNING = "RUNNING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Engine Schema 唯一持久化入口。 */
    public DagRunCoordinator(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 获取 Run 的排他行锁，调用方必须持有一个活动 JDBC 事务。 */
    public String lockRunProject(String runId) {
        List<String> owners = jdbc.query(
                "SELECT project_id FROM flow_dag_run WHERE id = ? FOR UPDATE",
                (rs, row) -> rs.getString(1), runId);
        return owners.isEmpty() ? null : owners.getFirst();
    }

    /**
     * 对已锁定的 WorkflowRun 计算 DAG 状态并原子持久化结果。
     * 恢复时再次执行不会重复生成 NodeRun 或 Outbox。
     */
    public void advanceLocked(String runId) {
        List<String[]> runs = jdbc.query(
                "SELECT graph_json, graph_sha256, input_json, status FROM flow_dag_run WHERE id = ?",
                (rs, row) -> new String[] {rs.getString(1), rs.getString(2),
                        rs.getString(3), rs.getString(4)}, runId);
        if (runs.isEmpty()) throw new IllegalArgumentException("Unknown DAG Run");
        String[] snapshot = runs.getFirst();
        if (SUCCEEDED.equals(snapshot[3])) return;
        DagDependencyPlanner.Plan plan = DagDependencyPlanner.load(snapshot[0], snapshot[1], mapper);
        final JsonNode input;
        try { input = mapper.readTree(snapshot[2]); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Invalid persisted DAG input", invalid); }

        List<NodeRow> rows = jdbc.query(
                "SELECT id, node_id, status, condition_decision FROM flow_dag_node_run "
                        + "WHERE dag_run_id = ? ORDER BY node_id",
                (rs, row) -> new NodeRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        (Boolean) rs.getObject(4)), runId);
        if (rows.size() != plan.nodes().size()) throw new IllegalStateException("Incomplete NodeRun snapshot");
        Map<String, DagDependencyPlanner.NodeState> states = new HashMap<>();
        Map<String, String> ids = new HashMap<>();
        int active = 0;
        for (NodeRow row : rows) {
            if (ids.putIfAbsent(row.nodeId(), row.id()) != null) {
                throw new IllegalStateException("Duplicate NodeRun");
            }
            DagDependencyPlanner.Phase phase = DagDependencyPlanner.Phase.valueOf(row.status());
            if (phase == DagDependencyPlanner.Phase.READY
                    || phase == DagDependencyPlanner.Phase.DISPATCHED) active++;
            states.put(row.nodeId(), new DagDependencyPlanner.NodeState(phase, row.decision()));
        }
        List<DagDependencyPlanner.Transition> changes = DagDependencyPlanner.advance(
                plan, states, input, Math.max(0, MAX_ACTIVE_NODES - active));
        Timestamp now = Timestamp.from(Instant.now());
        for (DagDependencyPlanner.Transition change : changes) {
            DagDependencyPlanner.NodeState state = change.state();
            String id = ids.get(change.nodeId());
            int updated = jdbc.update(
                    "UPDATE flow_dag_node_run SET status = ?, condition_decision = ?, updated_at = ? "
                            + "WHERE id = ? AND status = 'BLOCKED'",
                    state.phase().name(), state.decision(), now, id);
            if (updated != 1) throw new IllegalStateException("NodeRun CAS lost");
            states.put(change.nodeId(), state);
            if (state.phase() == DagDependencyPlanner.Phase.READY) {
                jdbc.update("INSERT INTO flow_dag_dispatch_outbox "
                                + "(node_run_id, event_id, delivery_state, attempts, created_at) "
                                + "VALUES (?, ?, 'PENDING', 0, ?)",
                        id, UUID.randomUUID().toString(), now);
            }
        }
        boolean failed = states.values().stream()
                .anyMatch(s -> s.phase() == DagDependencyPlanner.Phase.FAILED);
        boolean terminal = states.values().stream()
                .allMatch(s -> s.phase() == DagDependencyPlanner.Phase.SUCCEEDED
                        || s.phase() == DagDependencyPlanner.Phase.SKIPPED
                        || s.phase() == DagDependencyPlanner.Phase.FAILED);
        String status = failed ? FAILED : terminal ? SUCCEEDED : RUNNING;
        if (!status.equals(snapshot[3])) {
            jdbc.update("UPDATE flow_dag_run SET status = ?, updated_at = ? WHERE id = ?",
                    status, now, runId);
        }
    }

    /**
     * 对持久性派发冲突或超过重试预算的节点做 Fail-fast 收敛。
     * 只允许未终态节点被置为 FAILED，任何成功的旧结果不会被覆盖。
     */
    public void failDispatchLocked(String runId, String nodeRunId) {
        if (lockRunProject(runId) == null) throw new IllegalArgumentException("Unknown DAG Run");
        int updated = jdbc.update("UPDATE flow_dag_node_run SET status = 'FAILED', "
                        + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND dag_run_id = ? "
                        + "AND status IN ('READY', 'DISPATCHED')",
                nodeRunId, runId);
        if (updated == 1) advanceLocked(runId);
    }

    private record NodeRow(String id, String nodeId, String status, Boolean decision) { }
}
