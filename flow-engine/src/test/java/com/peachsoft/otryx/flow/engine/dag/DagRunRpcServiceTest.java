package com.peachsoft.otryx.flow.engine.dag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.contracts.v1.DagStartCommand;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 跨事务 NodeRun/Inbox/Outbox、幂等、版本固定及项目隔离的 H2 回归测试。 */
class DagRunRpcServiceTest {
    private DagTestDatabase db;
    private DagRunRpcService service;
    private DagNodeOutcomeService outcomes;
    private WorkflowRpc metadata;
    private String graph;

    @BeforeEach
    void setup() {
        db = new DagTestDatabase();
        service = new DagRunRpcService(db.jdbc, db.manager, db.mapper, db.coordinator);
        outcomes = new DagNodeOutcomeService(db.jdbc, db.manager, db.coordinator);
        metadata = mock(WorkflowRpc.class);
        ReflectionTestUtils.setField(service, "workflows", metadata);
        graph = DagTestDatabase.graph(DagTestDatabase.task("A"), "");
        revision(graph);
    }

    private void revision(String json) {
        when(metadata.revision(eq("admin"), eq(DagTestDatabase.PROJECT),
                eq(DagTestDatabase.WORKFLOW), eq(1))).thenReturn(
                new WorkflowRevisionView(DagTestDatabase.WORKFLOW, 1, "Published DAG",
                        json, DagTestDatabase.hash(json), 1, "2026-10-27T00:00:00Z"));
    }

    private DagStartCommand start(String key, String input) {
        return new DagStartCommand("admin", DagTestDatabase.PROJECT, DagTestDatabase.WORKFLOW,
                1, key, input);
    }

    @Test
    void sameIdempotencyKeyCreatesOnePersistedRunAndOneNodeOutbox() {
        String key = UUID.randomUUID().toString();
        var first = service.start(start(key, "{}"));
        assertEquals("CREATED", first.code());
        assertEquals("RUNNING", first.run().status());
        var replay = service.start(start(key, "{}"));
        assertEquals("DUPLICATE", replay.code());
        assertEquals(first.run().id(), replay.run().id());
        assertEquals("CONFLICT", service.start(start(key, "{\"different\":true}")).code());
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_run", Integer.class));
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_node_run", Integer.class));
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_dispatch_outbox", Integer.class));
        assertEquals(1, service.list("admin", DagTestDatabase.PROJECT).runs().length);
        assertEquals(0, service.list("foreign", DagTestDatabase.PROJECT).runs().length);
        assertNull(service.detail("foreign", DagTestDatabase.PROJECT, first.run().id()));
        assertNotNull(service.detail("admin", DagTestDatabase.PROJECT, first.run().id()));
    }

    @Test
    void atomicTerminalOutcomesAdvanceJoinAndDoNotDuplicateOnAckReplay() {
        graph = DagTestDatabase.graph(
                DagTestDatabase.task("A") + "," + DagTestDatabase.task("B"),
                DagTestDatabase.edge("ab", "A", "B"));
        revision(graph);
        var started = service.start(start(UUID.randomUUID().toString(), "{}"));
        var before = service.detail("admin", DagTestDatabase.PROJECT, started.run().id());
        assertEquals("READY", before.nodes()[0].status());
        assertEquals("BLOCKED", before.nodes()[1].status());
        String nodeA = before.nodes()[0].id();
        var eventA = event(nodeA, "SUCCEEDED");
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(eventA).code());
        assertEquals("DUPLICATE", outcomes.tryAcknowledge(eventA).code());
        assertEquals("CONFLICT", outcomes.tryAcknowledge(event(nodeA, "FAILED")).code());

        var half = service.detail("admin", DagTestDatabase.PROJECT, started.run().id());
        assertEquals("SUCCEEDED", half.nodes()[0].status());
        assertEquals("READY", half.nodes()[1].status());
        assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_dispatch_outbox", Integer.class));
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(event(half.nodes()[1].id(), "SUCCEEDED")).code());
        assertEquals("SUCCEEDED", service.detail("admin", DagTestDatabase.PROJECT,
                started.run().id()).run().status());
        assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_outcome_inbox", Integer.class));
        assertEquals(2, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_dispatch_outbox", Integer.class));
    }

    @Test
    void failureDoesNotActivateSuccessorsAndBadProjectCannotAcknowledge() {
        graph = DagTestDatabase.graph(DagTestDatabase.task("A") + "," + DagTestDatabase.task("B"),
                DagTestDatabase.edge("ab", "A", "B"));
        revision(graph);
        var started = service.start(start(UUID.randomUUID().toString(), "{}"));
        String node = service.detail("admin", DagTestDatabase.PROJECT, started.run().id()).nodes()[0].id();
        var mismatch = new EngineOutcomeCommand(UUID.randomUUID().toString(), node,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, "SUCCEEDED");
        assertEquals("CONFLICT", outcomes.tryAcknowledge(mismatch).code());
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(event(node, "FAILED")).code());
        var detail = service.detail("admin", DagTestDatabase.PROJECT, started.run().id());
        assertEquals("FAILED", detail.run().status());
        assertEquals("BLOCKED", detail.nodes()[1].status());
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_dispatch_outbox", Integer.class));
    }

    @Test
    void fixedRevisionHashRemainsOwnedEvenIfMetadataLaterChanges() {
        String key = UUID.randomUUID().toString();
        var run = service.start(start(key, "{}")).run();
        String original = run.graphSha256();
        revision(DagTestDatabase.graph(DagTestDatabase.task("A").replace("safe.echo","safe.fail"), ""));
        var persisted = service.detail("admin", DagTestDatabase.PROJECT, run.id());
        assertEquals(original, persisted.run().graphSha256());
        assertEquals("safe.echo", db.jdbc.queryForObject(
                "SELECT handler_key FROM flow_dag_node_run WHERE dag_run_id = ?",
                String.class, run.id()));
    }

    private static EngineOutcomeCommand event(String nodeId, String outcome) {
        return new EngineOutcomeCommand(UUID.randomUUID().toString(), nodeId,
                UUID.randomUUID().toString(), DagTestDatabase.PROJECT, 1, outcome);
    }
}
