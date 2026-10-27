package com.peachsoft.otryx.flow.engine.dag;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** 已发布工作流的并行、条件跳过、汇聚及失败隔离回归测试。 */
class DagDependencyPlannerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String task(String id) {
        return "{\"id\":\""+id+"\",\"type\":\"TASK\",\"handlerKey\":\"safe.echo\","
                +"\"parameters\":{},\"timeoutSeconds\":30,\"maxAttempts\":2}";
    }
    private static String edge(String from,String to,String branch) {
        return "{\"source\":\""+from+"\",\"target\":\""+to+"\",\"branch\":\""+branch+"\"}";
    }
    private static String graph(String nodes,String edges) {
        return "{\"schemaVersion\":1,\"nodes\":["+nodes+"],\"edges\":["+edges+"]}";
    }
    private static DagDependencyPlanner.Plan load(String graph) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(graph.getBytes(StandardCharsets.UTF_8)));
        return DagDependencyPlanner.load(graph,hash,JSON);
    }
    private static DagDependencyPlanner.NodeState done() {
        return new DagDependencyPlanner.NodeState(DagDependencyPlanner.Phase.SUCCEEDED,null);
    }

    @Test
    void diamondStartsParallelSiblingsButWaitsForBothBeforeJoin() throws Exception {
        String graph = graph(task("A")+","+task("B")+","+task("C")+","+task("D"),
                edge("A","B","ALWAYS")+","+edge("A","C","ALWAYS")+","
                +edge("B","D","ALWAYS")+","+edge("C","D","ALWAYS"));
        var plan = load(graph);
        var input = JSON.readTree("{}");
        assertEquals("A", DagDependencyPlanner.advance(plan,Map.of(),input,8).getFirst().nodeId());
        var both = DagDependencyPlanner.advance(plan,Map.of("A",done()),input,8);
        assertEquals(java.util.List.of("B","C"),
                both.stream().map(DagDependencyPlanner.Transition::nodeId).toList());
        assertTrue(DagDependencyPlanner.advance(plan,Map.of("A",done(),"B",done()),input,8)
                .stream().noneMatch(x -> x.nodeId().equals("D")));
        assertEquals("D",DagDependencyPlanner.advance(plan,Map.of("A",done(),"B",done(),"C",done()),input,8)
                .getFirst().nodeId());
    }

    @Test
    void conditionsSkipOnlyInactiveBranchAndRespectJoinBarrier() throws Exception {
        String condition = "{\"id\":\"A\",\"type\":\"CONDITION\","
                +"\"condition\":{\"op\":\"equals\",\"path\":\"$.region\",\"value\":\"CN\"}}";
        String dag = graph(condition+","+task("B")+","+task("C")+","+task("D"),
                edge("A","B","TRUE")+","+edge("A","C","FALSE")+","
                +edge("B","D","ALWAYS")+","+edge("C","D","ALWAYS"));
        var plan = load(dag);
        var input = JSON.readTree("{\"region\":\"CN\"}");
        var first = DagDependencyPlanner.advance(plan,Map.of(),input,8);
        assertEquals(java.util.List.of("A","B","C"),
                first.stream().map(DagDependencyPlanner.Transition::nodeId).toList());
        assertEquals(Boolean.TRUE,first.getFirst().state().decision());
        assertEquals(DagDependencyPlanner.Phase.SKIPPED,first.get(2).state().phase());
        Map<String,DagDependencyPlanner.NodeState> state = new HashMap<>();
        first.forEach(x -> state.put(x.nodeId(),x.state()));
        state.put("B",done());
        assertEquals("D",DagDependencyPlanner.advance(plan,state,input,8).getFirst().nodeId());
        var other = DagDependencyPlanner.advance(plan,Map.of(),
                JSON.readTree("{\"region\":\"US\"}"),8);
        assertEquals(Boolean.FALSE,other.getFirst().state().decision());
        assertEquals(DagDependencyPlanner.Phase.SKIPPED,other.get(1).state().phase());
    }

    @Test
    void boundedAdmissionAndFailureDoNotActivateDownstream() throws Exception {
        String graph = graph(task("A")+","+task("B")+","+task("C"),"");
        var plan = load(graph);
        assertEquals(2,DagDependencyPlanner.advance(plan,Map.of(),JSON.readTree("{}"),2).size());
        assertEquals(0,DagDependencyPlanner.advance(plan,Map.of(),JSON.readTree("{}"),0).size());
        var state = Map.of("A",new DagDependencyPlanner.NodeState(DagDependencyPlanner.Phase.FAILED,null));
        assertTrue(DagDependencyPlanner.advance(plan,state,JSON.readTree("{}"),8).isEmpty());
    }

    @Test
    void rejectsCorruptRevisionCyclesAndUnsafeHandlers() throws Exception {
        String valid = graph(task("A"),"");
        assertThrows(IllegalArgumentException.class,
                () -> DagDependencyPlanner.load(valid,"not-the-hash",JSON));
        String cycle = graph(task("A")+","+task("B"),
                edge("A","B","ALWAYS")+","+edge("B","A","ALWAYS"));
        assertThrows(IllegalArgumentException.class,() -> load(cycle));
        assertThrows(IllegalArgumentException.class,
                () -> load(graph(task("A").replace("safe.echo","java.lang.Runtime.exec"),"")));
    }
}

