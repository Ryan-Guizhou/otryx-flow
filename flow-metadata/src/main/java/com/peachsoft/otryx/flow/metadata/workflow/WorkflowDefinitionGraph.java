package com.peachsoft.otryx.flow.metadata.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 工作流 JSON Schema v1 的强制校验和确定性规范化。
 *
 * <p>仅允许可信 Java Handler 和有界字段比较条件；严禁脚本、反射和 SQL。
 */
public final class WorkflowDefinitionGraph {
    public static final int MAX_GRAPH_BYTES = 48 * 1024;
    private static final int MAX_NODE_PARAMS_BYTES = 16 * 1024;
    private static final int MAX_CONDITION_DEPTH = 6;
    private static final int MAX_LIST_VALUES = 20;
    private static final Set<String> TRUSTED_HANDLERS = Set.of("safe.echo", "safe.fail");
    private static final Set<String> TOP_FIELDS = Set.of("schemaVersion", "nodes", "edges");
    private static final Set<String> TASK_FIELDS = Set.of("id", "type", "label", "handlerKey",
            "parameters", "timeoutSeconds", "maxAttempts");
    private static final Set<String> CONDITION_FIELDS = Set.of("id", "type", "label", "condition");
    private static final Set<String> EDGE_FIELDS = Set.of("id", "source", "target", "branch");
    private static final Pattern PATH = Pattern.compile(
            "\\$\\.[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,7}");

    private WorkflowDefinitionGraph() { }

    /** 经后端校验后的规范化快照及哈希。 */
    public record Validated(String canonicalJson, String sha256, List<String> topologicalOrder) { }

    /**
     * 返回字节稳定的规范化图。只排序对象字段及节点/边 ID，保留有语义的数组顺序。
     *
     * @throws IllegalArgumentException JSON 不合法、越界、执行策略不可信或有环时
     */
    public static Validated validate(ObjectMapper mapper, String json) {
        if (mapper == null || json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_GRAPH_BYTES) {
            throw new IllegalArgumentException("Workflow graph exceeds bounded JSON size");
        }
        final JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Malformed workflow JSON", invalid);
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Graph must be an object");
        onlyFields(root, TOP_FIELDS);
        int version = integer(root.get("schemaVersion"), 1, 1);
        if (version != 1) throw new IllegalArgumentException("Unknown workflow schema version");
        JsonNode nodes = root.get("nodes");
        JsonNode edges = root.get("edges");
        if (nodes == null || !nodes.isArray() || edges == null || !edges.isArray()) {
            throw new IllegalArgumentException("Nodes and edges must be arrays");
        }
        if (nodes.size() == 0 || nodes.size() > WorkflowDagValidator.MAX_NODES
                || edges.size() > WorkflowDagValidator.MAX_EDGES) {
            throw new IllegalArgumentException("Graph cardinality exceeds bounds");
        }
        List<WorkflowDagValidator.Node> structuralNodes = new ArrayList<>();
        List<WorkflowDagValidator.Edge> structuralEdges = new ArrayList<>();
        Map<String, String> types = new HashMap<>();
        Map<String, Set<String>> branches = new HashMap<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) throw new IllegalArgumentException("Node must be an object");
            String id = text(node.get("id"), 128);
            String type = text(node.get("type"), 16);
            if (!type.equals("TASK") && !type.equals("CONDITION")) {
                throw new IllegalArgumentException("Unrecognized node type");
            }
            onlyFields(node, type.equals("TASK") ? TASK_FIELDS : CONDITION_FIELDS);
            if (node.has("label")) text(node.get("label"), 100);
            if (type.equals("TASK")) {
                String handler = text(node.get("handlerKey"), 128);
                if (!TRUSTED_HANDLERS.contains(handler)) {
                    throw new IllegalArgumentException("Handler is not registered in the trusted allowlist");
                }
                JsonNode params = node.get("parameters");
                if (params == null || !params.isObject()
                        || params.toString().getBytes(StandardCharsets.UTF_8).length > MAX_NODE_PARAMS_BYTES) {
                    throw new IllegalArgumentException("Task parameters must be a bounded object");
                }
                integer(node.get("timeoutSeconds"), 1, 86400);
                integer(node.get("maxAttempts"), 1, 10);
            } else {
                condition(node.get("condition"), 0);
                branches.put(id, new HashSet<>());
            }
            types.put(id, type);
            structuralNodes.add(new WorkflowDagValidator.Node(id));
        }
        for (JsonNode edge : edges) {
            if (!edge.isObject()) throw new IllegalArgumentException("Edge must be an object");
            onlyFields(edge, EDGE_FIELDS);
            String id = text(edge.get("id"), 128);
            String source = text(edge.get("source"), 128);
            String target = text(edge.get("target"), 128);
            String branch = text(edge.get("branch"), 16);
            String type = types.get(source);
            if (type == null) throw new IllegalArgumentException("Unknown edge source");
            if (type.equals("TASK") && !branch.equals("ALWAYS")) {
                throw new IllegalArgumentException("Task edges must use ALWAYS");
            }
            if (type.equals("CONDITION")) {
                if (!branch.equals("TRUE") && !branch.equals("FALSE")) {
                    throw new IllegalArgumentException("Condition edges must use TRUE or FALSE");
                }
                if (!branches.get(source).add(branch)) {
                    throw new IllegalArgumentException("Condition branch must be unique");
                }
            }
            structuralEdges.add(new WorkflowDagValidator.Edge(id, source, target));
        }
        for (Set<String> branchSet : branches.values()) {
            if (!branchSet.equals(Set.of("TRUE", "FALSE"))) {
                throw new IllegalArgumentException("Each condition requires TRUE and FALSE branches");
            }
        }
        WorkflowDagValidator.Result dag = WorkflowDagValidator.validate(structuralNodes, structuralEdges);
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("schemaVersion", 1);
        canonical.put("nodes", sortedCanonical(nodes));
        canonical.put("edges", sortedCanonical(edges));
        try {
            String snapshot = mapper.writeValueAsString(canonical);
            return new Validated(snapshot, sha256(snapshot), dag.topologicalOrder());
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Failed to normalize graph", invalid);
        }
    }

    private static List<Object> sortedCanonical(JsonNode array) {
        List<JsonNode> sorted = new ArrayList<>();
        array.forEach(sorted::add);
        sorted.sort(Comparator.comparing(n -> n.get("id").asText()));
        List<Object> converted = new ArrayList<>(sorted.size());
        for (JsonNode item : sorted) converted.add(canonical(item));
        return converted;
    }

    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> elements = new ArrayList<>();
            node.forEach(child -> elements.add(canonical(child)));
            return elements;
        }
        if (node.isNull()) return null;
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.numberValue();
        return node.asText();
    }

    private static void condition(JsonNode node, int depth) {
        if (depth > MAX_CONDITION_DEPTH || node == null || !node.isObject()) {
            throw new IllegalArgumentException("Invalid condition depth or object");
        }
        String op = text(node.get("op"), 16);
        if (op.equals("and") || op.equals("or")) {
            onlyFields(node, Set.of("op", "conditions"));
            JsonNode conditions = node.get("conditions");
            if (conditions == null || !conditions.isArray() || conditions.size() < 2
                    || conditions.size() > 10) {
                throw new IllegalArgumentException("Invalid condition operands");
            }
            for (JsonNode child : conditions) condition(child, depth + 1);
            return;
        }
        if (!Set.of("equals", "notEquals", "exists", "in").contains(op)) {
            throw new IllegalArgumentException("Unsupported conditional operation");
        }
        String path = text(node.get("path"), 128);
        if (!PATH.matcher(path).matches()) throw new IllegalArgumentException("Unsafe JSON path");
        if (op.equals("exists")) {
            onlyFields(node, Set.of("op", "path"));
            return;
        }
        if (op.equals("in")) {
            onlyFields(node, Set.of("op", "path", "values"));
            JsonNode values = node.get("values");
            if (values == null || !values.isArray() || values.isEmpty() || values.size() > MAX_LIST_VALUES) {
                throw new IllegalArgumentException("Invalid IN operands");
            }
            for (JsonNode value : values) scalar(value);
            return;
        }
        onlyFields(node, Set.of("op", "path", "value"));
        if (!node.has("value")) throw new IllegalArgumentException("Condition value missing");
        scalar(node.get("value"));
    }

    private static void scalar(JsonNode value) {
        if (value == null || (!value.isTextual() && !value.isNumber()
                && !value.isBoolean() && !value.isNull())
                || value.toString().getBytes(StandardCharsets.UTF_8).length > 256) {
            throw new IllegalArgumentException("Condition must compare bounded scalar values");
        }
    }

    private static int integer(JsonNode value, int min, int max) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.asInt() < min || value.asInt() > max) {
            throw new IllegalArgumentException("Invalid numeric workflow bound");
        }
        return value.asInt();
    }

    private static String text(JsonNode value, int max) {
        if (value == null || !value.isTextual() || value.asText().isBlank()
                || value.asText().length() > max || value.asText().indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid workflow text field");
        }
        return value.asText();
    }

    private static void onlyFields(JsonNode object, Set<String> allowed) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowed.contains(fields.next())) {
                throw new IllegalArgumentException("Unknown workflow field");
            }
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}

