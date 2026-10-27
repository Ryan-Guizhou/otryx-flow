package com.peachsoft.otryx.flow.metadata.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 工作流 DAG 结构验证回归测试。 */
class WorkflowDagValidatorTest {
    @Test
    void parallelDiamondProducesStableOrderAndDepth() {
        var nodes = List.of(new WorkflowDagValidator.Node("D"), new WorkflowDagValidator.Node("C"),
                new WorkflowDagValidator.Node("B"), new WorkflowDagValidator.Node("A"));
        var edges = List.of(new WorkflowDagValidator.Edge("ac", "A", "C"),
                new WorkflowDagValidator.Edge("ab", "A", "B"),
                new WorkflowDagValidator.Edge("bd", "B", "D"),
                new WorkflowDagValidator.Edge("cd", "C", "D"));
        var result = WorkflowDagValidator.validate(nodes, edges);
        assertEquals(List.of("A", "B", "C", "D"), result.topologicalOrder());
        assertEquals(3, result.maximumDepth());
    }

    @Test
    void rejectsCycleSelfLoopDuplicateAndUnknownReference() {
        var nodes = List.of(new WorkflowDagValidator.Node("A"), new WorkflowDagValidator.Node("B"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(nodes,
                List.of(new WorkflowDagValidator.Edge("a", "A", "B"),
                        new WorkflowDagValidator.Edge("b", "B", "A"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(nodes,
                List.of(new WorkflowDagValidator.Edge("a", "A", "A"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(nodes,
                List.of(new WorkflowDagValidator.Edge("a", "A", "B"),
                        new WorkflowDagValidator.Edge("b", "A", "B"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(nodes,
                List.of(new WorkflowDagValidator.Edge("a", "A", "C"))));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(
                List.of(new WorkflowDagValidator.Node("A"), new WorkflowDagValidator.Node("A")), List.of()));
    }

    @Test
    void rejectsEmptyOversizedAndExcessivelyDeepDag() {
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(List.of(), List.of()));
        var oversized = new ArrayList<WorkflowDagValidator.Node>();
        for (int i = 0; i <= WorkflowDagValidator.MAX_NODES; i++) {
            oversized.add(new WorkflowDagValidator.Node("n" + i));
        }
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(oversized, List.of()));

        var deepNodes = new ArrayList<WorkflowDagValidator.Node>();
        var deepEdges = new ArrayList<WorkflowDagValidator.Edge>();
        for (int i = 0; i <= WorkflowDagValidator.MAX_DEPTH; i++) {
            deepNodes.add(new WorkflowDagValidator.Node("n" + i));
            if (i > 0) {
                deepEdges.add(new WorkflowDagValidator.Edge("e" + i, "n" + (i - 1), "n" + i));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> WorkflowDagValidator.validate(deepNodes, deepEdges));
    }
}
