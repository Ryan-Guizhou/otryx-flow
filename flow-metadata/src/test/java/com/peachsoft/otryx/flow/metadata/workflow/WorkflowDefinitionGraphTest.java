package com.peachsoft.otryx.flow.metadata.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 对 DAG 参数、条件 AST、哈希稳定性和安全拒绝路径的独立回归。 */
class WorkflowDefinitionGraphTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIAMOND = """
            {"schemaVersion":1,"nodes":[
              {"id":"A","type":"TASK","handlerKey":"safe.echo","parameters":{},"timeoutSeconds":30,"maxAttempts":2},
              {"id":"B","type":"TASK","handlerKey":"safe.echo","parameters":{},"timeoutSeconds":30,"maxAttempts":2},
              {"id":"C","type":"TASK","handlerKey":"safe.echo","parameters":{},"timeoutSeconds":30,"maxAttempts":2},
              {"id":"D","type":"TASK","handlerKey":"safe.echo","parameters":{},"timeoutSeconds":30,"maxAttempts":2}
            ],"edges":[
              {"id":"ab","source":"A","target":"B","branch":"ALWAYS"},
              {"id":"ac","source":"A","target":"C","branch":"ALWAYS"},
              {"id":"bd","source":"B","target":"D","branch":"ALWAYS"},
              {"id":"cd","source":"C","target":"D","branch":"ALWAYS"}
            ]}
            """;

    @Test
    void stableCanonicalHashDoesNotDependOnObjectFieldOrNodeOrder() {
        var first = WorkflowDefinitionGraph.validate(JSON, DIAMOND);
        String reordered = DIAMOND.replace(
                "\"schemaVersion\":1,\"nodes\":[", "\"nodes\":[")
                .replace("],\"edges\":[", "],\"schemaVersion\":1,\"edges\":[");
        var second = WorkflowDefinitionGraph.validate(JSON, reordered);
        assertEquals(first.sha256(), second.sha256());
        assertEquals(first.canonicalJson(), second.canonicalJson());
        assertEquals(java.util.List.of("A", "B", "C", "D"), first.topologicalOrder());
        assertNotEquals("", first.sha256());
    }

    @Test
    void validatesOnlyBoundedConditionalComparisonsAndBranches() {
        String valid = """
                {"schemaVersion":1,
                  "nodes":[
                    {"id":"A","type":"CONDITION","condition":{"op":"and","conditions":[
                      {"op":"exists","path":"$.request.region"},
                      {"op":"in","path":"$.request.region","values":["CN","US"]}
                    ]}},
                    {"id":"B","type":"TASK","handlerKey":"safe.echo","parameters":{},"timeoutSeconds":30,"maxAttempts":1},
                    {"id":"C","type":"TASK","handlerKey":"safe.echo","parameters":{},"timeoutSeconds":30,"maxAttempts":1}
                  ],
                  "edges":[{"id":"ab","source":"A","target":"B","branch":"TRUE"},
                           {"id":"ac","source":"A","target":"C","branch":"FALSE"}]}
                """;
        assertEquals(2, WorkflowDagValidator.validate(
                java.util.List.of(new WorkflowDagValidator.Node("A"), new WorkflowDagValidator.Node("B")),
                java.util.List.of(new WorkflowDagValidator.Edge("ab", "A", "B"))).maximumDepth());
        WorkflowDefinitionGraph.validate(JSON, valid);
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, valid.replace("\"branch\":\"FALSE\"", "\"branch\":\"TRUE\"")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, valid.replace("$.request.region", "$.x[0]")));
    }

    @Test
    void rejectsCycleUntrustedHandlerAndScriptFields() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, DIAMOND.replace("\"target\":\"D\",\"branch\":\"ALWAYS\"}",
                        "\"target\":\"A\",\"branch\":\"ALWAYS\"}")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, DIAMOND.replace("safe.echo", "java.lang.Runtime.exec")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, DIAMOND.replace("\"type\":\"TASK\"",
                        "\"type\":\"TASK\",\"script\":\"System.exit(0)\"")));
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, DIAMOND.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
    }

    @Test
    void rejectsTooLargeAndNonObjectGraph() {
        assertThrows(IllegalArgumentException.class, () ->
                WorkflowDefinitionGraph.validate(JSON, "{" + " ".repeat(WorkflowDefinitionGraph.MAX_GRAPH_BYTES) + "}"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowDefinitionGraph.validate(JSON, "[]"));
    }
}

