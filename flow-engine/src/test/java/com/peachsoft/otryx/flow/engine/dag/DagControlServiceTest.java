package com.peachsoft.otryx.flow.engine.dag;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.contracts.v1.DagCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagRetryCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagStartCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.EngineOutcomeCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Engine V5 事务和取消/逻辑尝试历史的可重放单元测试。 */
class DagControlServiceTest {
    private DagTestDatabase db;
    private DagRunRpcService rpc;
    private DagControlService controls;
    private DagNodeOutcomeService outcomes;
    private DagCancelRelay relay;
    private DispatcherRpc dispatcher;
    private String runId;
    private String nodeRunId;

    @BeforeEach
    void prepare() {
        db = new DagTestDatabase();
        rpc = new DagRunRpcService(db.jdbc, db.manager, db.mapper, db.coordinator);
        controls = new DagControlService(db.jdbc, db.manager, db.coordinator);
        outcomes = new DagNodeOutcomeService(db.jdbc, db.manager, db.coordinator);
        relay = new DagCancelRelay(db.jdbc, db.manager, db.coordinator);
        dispatcher = mock(DispatcherRpc.class);
        ReflectionTestUtils.setField(relay, "dispatcher", dispatcher);
        WorkflowRpc metadata = mock(WorkflowRpc.class);
        when(metadata.revision("admin", DagTestDatabase.PROJECT,
                DagTestDatabase.WORKFLOW, 1)).thenReturn(new WorkflowRevisionView(
                DagTestDatabase.WORKFLOW, 1, "Test", DagTestDatabase.SINGLE,
                DagTestDatabase.hash(DagTestDatabase.SINGLE), 1, "2026-10-27T00:00:00Z"));
        ReflectionTestUtils.setField(rpc, "workflows", metadata);
        ReflectionTestUtils.setField(rpc, "controls", controls);
        var started = rpc.start(new DagStartCommand("admin", DagTestDatabase.PROJECT,
                DagTestDatabase.WORKFLOW, 1, UUID.randomUUID().toString(), "{}"));
        runId = started.run().id();
        nodeRunId = rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].id();
    }

    private DagCancelCommand cancel(String key, long version) {
        return new DagCancelCommand("admin", DagTestDatabase.PROJECT, runId, version, key);
    }

    private DagRetryCommand retry(String key, long version) {
        return new DagRetryCommand("admin", DagTestDatabase.PROJECT, runId, "A", version, key);
    }

    private EngineOutcomeCommand outcome(String nodeId, String status) {
        return new EngineOutcomeCommand(UUID.randomUUID().toString(), nodeId,
                UUID.randomUUID().toString(), DagTestDatabase.PROJECT, 1, status);
    }

    @Test
    void cancelBeforeDispatchStagesRemoteFenceAndDeduplicatesControlKey() {
        long version = rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().runVersion();
        String key = UUID.randomUUID().toString();
        var accepted = controls.cancel(cancel(key, version));
        assertEquals("OK", accepted.code());
        assertEquals("CANCEL_REQUESTED", accepted.run().status());
        assertEquals("CANCEL_PENDING", rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].status());
        assertEquals("CANCELLED", db.jdbc.queryForObject(
                "SELECT delivery_state FROM flow_dag_dispatch_outbox WHERE node_run_id=?",
                String.class, nodeRunId));
        assertEquals("DUPLICATE", controls.cancel(cancel(key, version)).code());
        assertEquals("CONFLICT", controls.cancel(cancel(key, version + 1)).code());
        assertEquals("CONFLICT", controls.cancel(cancel(UUID.randomUUID().toString(), version)).code());
        assertEquals("NOT_FOUND", controls.cancel(new DagCancelCommand(
                "intruder", DagTestDatabase.PROJECT, runId, version,
                UUID.randomUUID().toString())).code());
        assertEquals(1, controls.history("admin", DagTestDatabase.PROJECT, runId).events().length);
        assertEquals(0, controls.history("intruder", DagTestDatabase.PROJECT, runId).events().length);
        when(dispatcher.cancelDagTask(any())).thenReturn(new DispatchCancelReply("CANCELLED", "CANCELLED"));
        assertEquals(1, relay.relayOnce());
        assertEquals("CANCELLED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        assertEquals("CANCELLED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].status());
        assertEquals("CONFLICT", outcomes.tryAcknowledge(outcome(nodeRunId,"SUCCEEDED")).code());
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_outcome_inbox",Integer.class));
    }

    @Test
    void terminalWorkerSuccessIsNotOverwrittenByLateCancellation() {
        var terminal = outcome(nodeRunId, "SUCCEEDED");
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(terminal).code());
        assertEquals("SUCCEEDED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        var result = controls.cancel(cancel(UUID.randomUUID().toString(),
                rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().runVersion()));
        assertEquals("CONFLICT", result.code());
        assertEquals("SUCCEEDED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].status());
        assertEquals("DUPLICATE", outcomes.tryAcknowledge(terminal).code());
        assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_cancel_outbox",Integer.class));
    }

    @Test
    void workerOutcomeDuringCancellationRetainsItsRealResultAndFinishesCancelledRun() {
        long version = rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().runVersion();
        controls.cancel(cancel(UUID.randomUUID().toString(), version));
        var terminal = outcome(nodeRunId, "SUCCEEDED");
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(terminal).code());
        assertEquals("SUCCEEDED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].status());
        assertEquals("CANCELLED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        when(dispatcher.cancelDagTask(any())).thenReturn(new DispatchCancelReply("TERMINAL","SUCCEEDED"));
        assertEquals(1, relay.relayOnce());
        assertEquals("SUCCEEDED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes()[0].status());
        assertEquals("DUPLICATE", outcomes.tryAcknowledge(terminal).code());
    }

    @Test
    void retryCreatesFreshLogicalAttemptAndOldWorkerReplayCannotOverwriteIt() {
        var terminal = outcome(nodeRunId, "FAILED");
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(terminal).code());
        assertEquals("FAILED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        long version = rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().runVersion();
        String key = UUID.randomUUID().toString();
        assertEquals("CONFLICT", controls.retry(retry(key, version)).code());
        db.jdbc.update("UPDATE flow_dag_node_run SET updated_at=TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE id=?", nodeRunId);
        var retried = controls.retry(retry(key, version));
        assertEquals("OK", retried.code());
        assertNotEquals(nodeRunId, retried.nodeRunId());
        assertEquals("RUNNING", retried.run().status());
        assertTrue(retried.run().runVersion() > version);
        assertEquals("DUPLICATE", controls.retry(retry(key, version)).code());
        assertEquals("CONFLICT", controls.retry(retry(UUID.randomUUID().toString(), version)).code());
        var history = rpc.detail("admin", DagTestDatabase.PROJECT, runId).nodes();
        assertEquals(2, history.length);
        assertEquals("FAILED", history[0].status());
        assertEquals("READY", history[1].status());
        assertEquals(1, history[0].logicalAttempt());
        assertEquals(2, history[1].logicalAttempt());
        assertEquals("DUPLICATE", outcomes.tryAcknowledge(terminal).code());
        assertEquals("RUNNING", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        assertEquals(2, db.jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_dag_dispatch_outbox", Integer.class));
        assertEquals("ACCEPTED", outcomes.tryAcknowledge(outcome(retried.nodeRunId(),"SUCCEEDED")).code());
        assertEquals("SUCCEEDED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        assertEquals(1, controls.history("admin", DagTestDatabase.PROJECT, runId).events().length);
        assertEquals("RETRY", controls.history("admin", DagTestDatabase.PROJECT, runId).events()[0].action());
    }

    @Test
    void cancellationRelayLostAckRetriesSameNodeAndNeverCreatesAnotherOutbox() {
        long version = rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().runVersion();
        controls.cancel(cancel(UUID.randomUUID().toString(),version));
        when(dispatcher.cancelDagTask(any())).thenThrow(new IllegalStateException("lost ack"))
                .thenReturn(new DispatchCancelReply("CANCELLED","CANCELLED"));
        assertEquals(0, relay.relayOnce());
        db.jdbc.update("UPDATE flow_dag_cancel_outbox SET next_attempt_at=TIMESTAMP '2000-01-01 00:00:00'");
        assertEquals(1, relay.relayOnce());
        assertEquals("CANCELLED", rpc.detail("admin", DagTestDatabase.PROJECT, runId).run().status());
        assertEquals(1, db.jdbc.queryForObject("SELECT COUNT(*) FROM flow_dag_cancel_outbox",Integer.class));
        assertEquals("DELIVERED", db.jdbc.queryForObject(
                "SELECT delivery_state FROM flow_dag_cancel_outbox",String.class));
    }
}
