package com.peachsoft.otryx.flow.api.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.contracts.v1.WorkflowMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowView;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** 工作流 REST 状态映射的无网络测试；真实权限由 Spring Security 及 E2E 验证。 */
class WorkflowControllerTest {
    private final WorkflowController controller = new WorkflowController();
    private final WorkflowRpc rpc = mock(WorkflowRpc.class);
    private final Principal principal = () -> "admin";
    private final String project = UUID.randomUUID().toString();
    private final String workflow = UUID.randomUUID().toString();
    private final String key = UUID.randomUUID().toString();
    private final String graph = "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}";

    @BeforeEach
    void setUp() { ReflectionTestUtils.setField(controller, "workflows", rpc); }

    @Test
    void createIs201AndIdempotentReplayIs200() {
        when(rpc.createDraft(any())).thenReturn(reply("OK", false), reply("OK", true));
        var body = new WorkflowController.DraftBody("Example", graph);
        assertEquals(HttpStatus.CREATED, controller.create(principal, project, key, body).getStatusCode());
        assertEquals(HttpStatus.OK, controller.create(principal, project, key, body).getStatusCode());
    }

    @Test
    void mapsIllegalDagStaleDraftAndForeignWorkflow() {
        when(rpc.createDraft(any())).thenReturn(reply("INVALID", false));
        assertEquals(HttpStatus.BAD_REQUEST, controller.create(principal, project, key,
                new WorkflowController.DraftBody("Invalid", graph)).getStatusCode());
        when(rpc.saveDraft(any())).thenReturn(reply("CONFLICT", false), reply("NOT_FOUND", false));
        var body = new WorkflowController.UpdateBody(1, "Update", graph);
        assertEquals(HttpStatus.CONFLICT, controller.save(principal, project, workflow, body).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.save(principal, project, workflow, body).getStatusCode());
    }

    @Test
    void badIdentifiersAndMalformedGraphNeverCallRpc() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.list(principal, "bad").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.create(principal, project, key,
                new WorkflowController.DraftBody("Bad", "[]")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.publish(principal, project, "bad", key,
                new WorkflowController.PublishBody(1)).getStatusCode());
        verifyNoInteractions(rpc);
    }

    private WorkflowMutationReply reply(String code, boolean duplicate) {
        return new WorkflowMutationReply(code,
                new WorkflowView(workflow, project, "Demo", graph, 1, 0, 0,
                        "2026-10-27T00:00:00Z"), duplicate);
    }
}

