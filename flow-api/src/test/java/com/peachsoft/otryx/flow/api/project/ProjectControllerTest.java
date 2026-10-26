package com.peachsoft.otryx.flow.api.project;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.peachsoft.otryx.flow.contracts.v1.ProjectCreatedReply;
import com.peachsoft.otryx.flow.contracts.v1.ProjectRpc;
import com.peachsoft.otryx.flow.contracts.v1.ProjectView;
import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests HTTP status mapping for the durable RPC idempotency contract. */
class ProjectControllerTest {
    private final ProjectController controller = new ProjectController();
    private final ProjectRpc rpc = mock(ProjectRpc.class);
    private final Principal admin = () -> "admin";
    private final String key = "00000000-0000-4000-8000-000000000010";

    @BeforeEach
    void prepare() { ReflectionTestUtils.setField(controller, "projectRpc", rpc); }

    @Test
    void newProjectReturns201() {
        when(rpc.create(any())).thenReturn(reply(false, false));
        assertEquals(HttpStatus.CREATED,
                controller.create(admin, key, new ProjectController.NewProject("demo")).getStatusCode());
    }

    @Test
    void idempotentReplayReturns200() {
        when(rpc.create(any())).thenReturn(reply(true, false));
        assertEquals(HttpStatus.OK,
                controller.create(admin, key, new ProjectController.NewProject("demo")).getStatusCode());
    }

    @Test
    void changedPayloadOnSameRequestKeyReturns409() {
        when(rpc.create(any())).thenReturn(reply(true, true));
        assertEquals(HttpStatus.CONFLICT,
                controller.create(admin, key, new ProjectController.NewProject("other")).getStatusCode());
    }

    @Test
    void invalidKeyReturns400BeforeRpcInvocation() {
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.create(admin, "bad", new ProjectController.NewProject("demo")).getStatusCode());
        verifyNoInteractions(rpc);
    }

    private static ProjectCreatedReply reply(boolean duplicate, boolean conflict) {
        return new ProjectCreatedReply(new ProjectView("id", "demo", "2026-10-26T00:00:00Z"),
                duplicate, conflict);
    }
}
