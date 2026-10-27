package com.peachsoft.otryx.flow.engine.dag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 已发布 DAG 的无副作用依赖推进器；不持久化状态，也不通过 RPC 派发任务。
 * Engine 必须在同一 Run 数据库行锁中读取状态并提交推进结果。
 */
public final class DagDependencyPlanner {
    private static final int MAX_NODES = 100, MAX_EDGES = 500, MAX_DEPTH = 32;
    private static final Pattern JSON_PATH = Pattern.compile(
            "\\$\\.[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,7}");
    private static final Set<String> HANDLERS = Set.of("safe.echo", "safe.fail");

    private DagDependencyPlanner() { }

    /** BLOCKED 不可交给 Dispatcher；Ready 也尚不等于已执行。 */
    public enum Phase { BLOCKED, READY, DISPATCHED, SUCCEEDED, FAILED, SKIPPED, CANCEL_PENDING, CANCELLED }
    /** CONDITION 成功后必须持久化真实 TRUE/FALSE 分支决策。 */
    public record NodeState(Phase phase, Boolean decision) { }
    /** 发布版本的节点定义。 */
    public record Node(String id, String type, JsonNode expression) { }
    /** 带条件分支选择的有向依赖。 */
    public record Edge(String source, String target, String branch) { }
    /** 可确定性重放的只读拓扑。 */
    public record Plan(Map<String, Node> nodes, List<String> order, Map<String, List<Edge>> incoming) { }
    /** 节点推进结果；必须由 Engine 的数据库事务提交。 */
    public record Transition(String nodeId, NodeState state) { }

    /**
     * 验证不可变版本哈希并解析有界、无环依赖拓扑。
     * 生产调用方应先从 Metadata RPC 获取发布的 graphJson+graphSha256。
     */
    public static Plan load(String json, String sha256, ObjectMapper mapper) {
        if (json == null || sha256 == null || mapper == null
                || json.getBytes(StandardCharsets.UTF_8).length > 48 * 1024
                || !fingerprint(json).equals(sha256)) {
            throw new IllegalArgumentException("Published DAG snapshot hash mismatch");
        }
        final JsonNode graph;
        try {
            graph = mapper.readTree(json);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Malformed published DAG", invalid);
        }
        if (graph == null || !graph.isObject() || graph.path("schemaVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("Unsupported DAG schema");
        }
        JsonNode nodes = graph.path("nodes"), edges = graph.path("edges");
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > MAX_NODES
                || !edges.isArray() || edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("DAG collection bounds violated");
        }
        Map<String, Node> definitions = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<Edge>> incoming = new HashMap<>();
        Map<String, Set<String>> conditionBranches = new HashMap<>();
        for (JsonNode item : nodes) {
            String id = text(item, "id", 128), type = text(item, "type", 16);
            if (!Set.of("TASK", "CONDITION").contains(type)) throw new IllegalArgumentException("Unknown node type");
            if (type.equals("TASK") && (!HANDLERS.contains(text(item, "handlerKey", 128))
                    || !item.path("parameters").isObject()
                    || item.path("timeoutSeconds").asInt(-1) < 1
                    || item.path("timeoutSeconds").asInt(-1) > 86400
                    || item.path("maxAttempts").asInt(-1) < 1
                    || item.path("maxAttempts").asInt(-1) > 10)) {
                throw new IllegalArgumentException("Invalid or untrusted task");
            }
            JsonNode expression = type.equals("CONDITION") ? item.path("condition") : null;
            if (type.equals("CONDITION") && !expression.isObject()) {
                throw new IllegalArgumentException("Missing condition expression");
            }
            if (definitions.putIfAbsent(id, new Node(id, type,
                    expression == null ? null : expression.deepCopy())) != null) {
                throw new IllegalArgumentException("Duplicate DAG node ID");
            }
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<>());
            incoming.put(id, new ArrayList<>());
            if (type.equals("CONDITION")) conditionBranches.put(id, new HashSet<>());
        }
        Set<String> pairs = new HashSet<>();
        for (JsonNode item : edges) {
            String source = text(item, "source", 128), target = text(item, "target", 128);
            String branch = text(item, "branch", 16);
            if (!definitions.containsKey(source) || !definitions.containsKey(target)
                    || source.equals(target) || !pairs.add(source + "\u0000" + target)) {
                throw new IllegalArgumentException("Invalid DAG dependency");
            }
            if (definitions.get(source).type().equals("TASK")) {
                if (!branch.equals("ALWAYS")) throw new IllegalArgumentException("Invalid task edge");
            } else if (!Set.of("TRUE","FALSE").contains(branch)
                    || !conditionBranches.get(source).add(branch)) {
                throw new IllegalArgumentException("Invalid conditional branch");
            }
            incoming.get(target).add(new Edge(source, target, branch));
            outgoing.get(source).add(target);
            indegree.merge(target, 1, Integer::sum);
        }
        if (conditionBranches.values().stream().anyMatch(x -> !x.equals(Set.of("TRUE","FALSE")))) {
            throw new IllegalArgumentException("Condition requires two branch exits");
        }
        Map<String, Integer> depths = new HashMap<>();
        PriorityQueue<String> queue = new PriorityQueue<>();
        indegree.forEach((id, count) -> {
            if (count == 0) { queue.add(id); depths.put(id, 1); }
        });
        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.remove();
            sorted.add(id);
            if (depths.get(id) > MAX_DEPTH) throw new IllegalArgumentException("DAG depth exceeded");
            for (String child : outgoing.get(id)) {
                depths.merge(child, depths.get(id) + 1, Math::max);
                if (indegree.merge(child, -1, Integer::sum) == 0) queue.add(child);
            }
        }
        if (sorted.size() != definitions.size()) throw new IllegalArgumentException("DAG has a cycle");
        incoming.replaceAll((id, value) -> List.copyOf(value));
        return new Plan(Map.copyOf(definitions), List.copyOf(sorted), Map.copyOf(incoming));
    }

    /**
     * 计算本轮推进建议。所有上游必须完成；未选中边 SKIPPED；并行分支可以同时 READY。
     * 正在执行的节点不可被提前确认为成功；失败后不再新派发任何节点。
     */
    public static List<Transition> advance(Plan plan, Map<String, NodeState> persisted,
            JsonNode immutableInput, int maxNewTasks) {
        if (plan == null || persisted == null || immutableInput == null || !immutableInput.isObject()
                || immutableInput.toString().getBytes(StandardCharsets.UTF_8).length > 16384
                || maxNewTasks < 0 || maxNewTasks > MAX_NODES
                || !plan.nodes().keySet().containsAll(persisted.keySet())) {
            throw new IllegalArgumentException("Invalid DAG state snapshot");
        }
        Map<String, NodeState> states = new HashMap<>(persisted);
        for (String id : plan.nodes().keySet()) {
            states.putIfAbsent(id, new NodeState(Phase.BLOCKED, null));
        }
        if (states.values().stream().anyMatch(s -> s.phase() == Phase.FAILED)) return List.of();
        List<Transition> updated = new ArrayList<>();
        for (String id : plan.order()) {
            if (states.get(id).phase() != Phase.BLOCKED) continue;
            List<Edge> incoming = plan.incoming().get(id);
            boolean selected = incoming.isEmpty(), settled = true;
            for (Edge edge : incoming) {
                NodeState parent = states.get(edge.source());
                if (parent.phase() != Phase.SUCCEEDED && parent.phase() != Phase.SKIPPED) {
                    settled = false; break;
                }
                if (parent.phase() == Phase.SKIPPED) continue;
                Node parentNode = plan.nodes().get(edge.source());
                if (parentNode.type().equals("TASK")) {
                    selected = true;
                } else {
                    if (parent.decision() == null) throw new IllegalArgumentException("Unpersisted condition decision");
                    selected |= (parent.decision() == edge.branch().equals("TRUE"));
                }
            }
            if (!settled) continue;
            NodeState result;
            if (!selected) result = new NodeState(Phase.SKIPPED, null);
            else if (plan.nodes().get(id).type().equals("CONDITION")) {
                result = new NodeState(Phase.SUCCEEDED,
                        evaluate(plan.nodes().get(id).expression(), immutableInput, 0));
            } else if (maxNewTasks-- > 0) result = new NodeState(Phase.READY, null);
            else continue;
            states.put(id, result);
            updated.add(new Transition(id, result));
        }
        return List.copyOf(updated);
    }

    private static boolean evaluate(JsonNode expr, JsonNode input, int depth) {
        if (expr == null || !expr.isObject() || depth > 6) {
            throw new IllegalArgumentException("Unsafe conditional AST");
        }
        String op = text(expr, "op", 16);
        if (op.equals("and") || op.equals("or")) {
            JsonNode terms = expr.path("conditions");
            if (!terms.isArray() || terms.size() < 2 || terms.size() > 10) {
                throw new IllegalArgumentException("Invalid condition operands");
            }
            boolean result = op.equals("and");
            for (JsonNode term : terms) {
                boolean child = evaluate(term, input, depth + 1);
                if (op.equals("and")) result &= child;
                else result |= child;
            }
            return result;
        }
        if (!Set.of("equals","notEquals","exists","in").contains(op)) {
            throw new IllegalArgumentException("Unsupported condition op");
        }
        String path = text(expr, "path", 128);
        if (!JSON_PATH.matcher(path).matches()) throw new IllegalArgumentException("Unsafe JSON path");
        JsonNode candidate = input;
        for (String member : path.substring(2).split("\\.")) {
            candidate = candidate == null ? null : candidate.get(member);
        }
        if (op.equals("exists")) return candidate != null && !candidate.isNull();
        if (candidate == null || candidate.isNull()) return false;
        if (op.equals("in")) {
            JsonNode values = expr.path("values");
            if (!values.isArray() || values.isEmpty() || values.size() > 20) {
                throw new IllegalArgumentException("Invalid IN operands");
            }
            for (JsonNode value : values) if (candidate.equals(value)) return true;
            return false;
        }
        if (!expr.has("value")) throw new IllegalArgumentException("Missing comparison value");
        boolean matches = candidate.equals(expr.get("value"));
        return op.equals("equals") ? matches : !matches;
    }

    private static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank()
                || value.asText().length() > max || value.asText().indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid DAG " + key);
        }
        return value.asText();
    }

    private static String fingerprint(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }
}
