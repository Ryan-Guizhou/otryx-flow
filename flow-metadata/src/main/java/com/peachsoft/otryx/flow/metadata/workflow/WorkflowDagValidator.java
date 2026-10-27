package com.peachsoft.otryx.flow.metadata.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * 工作流 DAG 的确定性边界与拓扑验证器。
 *
 * <p>只处理结构化、可信服务端模型；JSON 反序列化与 Handler 白名单检查由调用层负责。
 */
public final class WorkflowDagValidator {
    public static final int MAX_NODES = 100;
    public static final int MAX_EDGES = 500;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_IDENTIFIER_LENGTH = 128;

    private WorkflowDagValidator() {
    }

    /** DAG 节点的稳定标识。 */
    public record Node(String id) {
    }

    /** DAG 中有向依赖关系。 */
    public record Edge(String id, String source, String target) {
    }

    /** 验证结果包含确定性拓扑顺序与最大拓扑深度。 */
    public record Result(List<String> topologicalOrder, int maximumDepth) {
    }

    /**
     * 校验非空 DAG、唯一标识、引用、环路与有界拓扑深度。
     *
     * @throws IllegalArgumentException 图不合法时抛出
     */
    public static Result validate(List<Node> nodes, List<Edge> edges) {
        if (nodes == null || nodes.isEmpty() || nodes.size() > MAX_NODES) {
            throw new IllegalArgumentException("Node count is out of bounds");
        }
        if (edges == null || edges.size() > MAX_EDGES) {
            throw new IllegalArgumentException("Edge count is out of bounds");
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Node node : nodes) {
            if (node == null) {
                throw new IllegalArgumentException("Null node");
            }
            requireIdentifier(node.id());
            if (indegree.putIfAbsent(node.id(), 0) != null) {
                throw new IllegalArgumentException("Duplicate node ID");
            }
            adjacency.put(node.id(), new ArrayList<>());
        }
        Set<String> edgeIds = new HashSet<>();
        Set<String> directedPairs = new HashSet<>();
        for (Edge edge : edges) {
            if (edge == null) {
                throw new IllegalArgumentException("Null edge");
            }
            requireIdentifier(edge.id());
            requireIdentifier(edge.source());
            requireIdentifier(edge.target());
            if (!edgeIds.add(edge.id())) {
                throw new IllegalArgumentException("Duplicate edge ID");
            }
            if (!indegree.containsKey(edge.source()) || !indegree.containsKey(edge.target())) {
                throw new IllegalArgumentException("Unknown edge endpoint");
            }
            if (Objects.equals(edge.source(), edge.target())) {
                throw new IllegalArgumentException("Self-loop");
            }
            if (!directedPairs.add(edge.source() + "\u0000" + edge.target())) {
                throw new IllegalArgumentException("Duplicate directed edge");
            }
            adjacency.get(edge.source()).add(edge.target());
            indegree.merge(edge.target(), 1, Integer::sum);
        }

        // 按节点标识稳定排序，避免相同 DAG 随输入顺序产生不同结果。
        Queue<String> ready = new java.util.PriorityQueue<>();
        Map<String, Integer> depth = new HashMap<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
                depth.put(entry.getKey(), 1);
            }
        }
        if (ready.isEmpty()) {
            throw new IllegalArgumentException("DAG must have an entry node");
        }
        List<String> ordered = new ArrayList<>(nodes.size());
        int maximumDepth = 0;
        while (!ready.isEmpty()) {
            String current = ready.remove();
            ordered.add(current);
            int currentDepth = depth.get(current);
            maximumDepth = Math.max(maximumDepth, currentDepth);
            if (maximumDepth > MAX_DEPTH) {
                throw new IllegalArgumentException("Maximum DAG depth exceeded");
            }
            for (String next : adjacency.get(current)) {
                depth.merge(next, currentDepth + 1, Math::max);
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (ordered.size() != nodes.size()) {
            throw new IllegalArgumentException("DAG contains a cycle");
        }
        return new Result(List.copyOf(ordered), maximumDepth);
    }

    private static void requireIdentifier(String id) {
        if (id == null || id.isBlank() || id.length() > MAX_IDENTIFIER_LENGTH
                || id.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid DAG identifier");
        }
    }
}
