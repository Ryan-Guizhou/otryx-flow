package com.peachsoft.otryx.flow.engine.dag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.contracts.v1.DagStartCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 依赖可靠 Outbox 独立于 Worker 的派发与丢失 ACK 重投测试。 */
class DagDispatchRelayTest {
    @Test
    void nodeIdentityIsNotParentRunAndAckReplayIsIdempotent() {
        DagTestDatabase db = new DagTestDatabase();
        DagRunRpcService start = new DagRunRpcService(db.jdbc, db.manager, db.mapper, db.coordinator);
        WorkflowRpc metadata = mock(WorkflowRpc.class);
        String graph = DagTestDatabase.SINGLE;
        when(metadata.revision("admin", DagTestDatabase.PROJECT, DagTestDatabase.WORKFLOW, 1))
                .thenReturn(new WorkflowRevisionView(DagTestDatabase.WORKFLOW, 1, "Graph",
                        graph, DagTestDatabase.hash(graph), 1, "2026-10-27T00:00:00Z"));
        ReflectionTestUtils.setField(start, "workflows", metadata);
        var created = start.start(new DagStartCommand("admin", DagTestDatabase.PROJECT,
                DagTestDatabase.WORKFLOW, 1, UUID.randomUUID().toString(), "{}"));
        String runId = created.run().id();
        String nodeId = start.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].id();
        DagDispatchRelay relay = new DagDispatchRelay(db.jdbc, db.manager, db.coordinator);
        DispatcherRpc dispatcher = mock(DispatcherRpc.class);
        when(dispatcher.enqueue(any())).thenReturn(new DispatchEnqueueReply(
                "CREATED", UUID.randomUUID().toString(), "READY"));
        ReflectionTestUtils.setField(relay, "dispatcher", dispatcher);
        assertEquals(1, relay.relayOnce());
        ArgumentCaptor<DispatchEnqueueCommand> captured =
                ArgumentCaptor.forClass(DispatchEnqueueCommand.class);
        verify(dispatcher).enqueue(captured.capture());
        assertEquals(nodeId, captured.getValue().runId());
        assertEquals(runId, captured.getValue().scheduleId());
        assertEquals(DagTestDatabase.WORKFLOW, captured.getValue().jobId());
        assertEquals("DISPATCHED",
                start.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].status());
        assertEquals(1, db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_dag_dispatch_outbox WHERE delivery_state='DELIVERED'",
                Integer.class));

        // Force the same durable envelope after a simulated lost HTTP/RPC ACK.
        db.jdbc.update("UPDATE flow_dag_dispatch_outbox SET delivery_state='PENDING', "
                + "claim_token=NULL,lease_until=NULL,next_attempt_at='1970-01-01 00:00:00'");
        when(dispatcher.enqueue(any())).thenReturn(new DispatchEnqueueReply(
                "DUPLICATE", UUID.randomUUID().toString(), "READY"));
        assertEquals(1, relay.relayOnce());
        assertEquals(1, db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_dag_dispatch_outbox", Integer.class));
    }

    @Test
    void permanentDispatchConflictFailsWorkflowWithoutWritingWorkerOutcome() {
        DagTestDatabase db = new DagTestDatabase();
        DagRunRpcService start = new DagRunRpcService(db.jdbc, db.manager, db.mapper, db.coordinator);
        WorkflowRpc metadata = mock(WorkflowRpc.class);
        when(metadata.revision("admin", DagTestDatabase.PROJECT, DagTestDatabase.WORKFLOW, 1))
                .thenReturn(new WorkflowRevisionView(DagTestDatabase.WORKFLOW, 1, "Graph",
                        DagTestDatabase.SINGLE, DagTestDatabase.hash(DagTestDatabase.SINGLE),
                        1, "2026-10-27T00:00:00Z"));
        ReflectionTestUtils.setField(start, "workflows", metadata);
        var created = start.start(new DagStartCommand("admin", DagTestDatabase.PROJECT,
                DagTestDatabase.WORKFLOW, 1, UUID.randomUUID().toString(), "{}"));
        DagDispatchRelay relay = new DagDispatchRelay(db.jdbc, db.manager, db.coordinator);
        DispatcherRpc dispatcher = mock(DispatcherRpc.class);
        when(dispatcher.enqueue(any())).thenReturn(new DispatchEnqueueReply("CONFLICT", null, null));
        ReflectionTestUtils.setField(relay, "dispatcher", dispatcher);
        assertEquals(0, relay.relayOnce());
        assertEquals("FAILED", start.detail("admin", DagTestDatabase.PROJECT,
                created.run().id()).run().status());
        assertEquals(0, db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_dag_outcome_inbox", Integer.class));
    }
}
