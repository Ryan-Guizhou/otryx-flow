package com.peachsoft.otryx.flow.api.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.peachsoft.otryx.flow.contracts.v1.DagRpc;
import com.peachsoft.otryx.flow.contracts.v1.DagRunView;
import com.peachsoft.otryx.flow.contracts.v1.DagStartReply;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** M3 手动 DAG 启动的 REST 错误映射与输入防护。 */
class DagRunControllerTest {
    private final DagRunController controller = new DagRunController();
    private final DagRpc rpc = mock(DagRpc.class);
    private final Principal principal = () -> "admin";
    private final String project = UUID.randomUUID().toString();
    private final String workflow = UUID.randomUUID().toString();
    private final String key = UUID.randomUUID().toString();

    @BeforeEach
    void prepare() { ReflectionTestUtils.setField(controller, "dag", rpc); }

    @Test
    void createdAndDuplicateReplaysReturnCorrectHttpStatus() {
        when(rpc.start(any())).thenReturn(reply("CREATED"), reply("DUPLICATE"));
        var body = new DagRunController.StartBody(workflow, 1, "{}");
        assertEquals(HttpStatus.CREATED, controller.start(principal, project, key, body).getStatusCode());
        assertEquals(HttpStatus.OK, controller.start(principal, project, key, body).getStatusCode());
    }

    @Test
    void staleVersionMissingRevisionAndInvalidBodyCannotBecomeSuccess() {
        when(rpc.start(any())).thenReturn(reply("NOT_FOUND"), reply("CONFLICT"), reply("INVALID"));
        var body = new DagRunController.StartBody(workflow, 1, "{}");
        assertEquals(HttpStatus.NOT_FOUND, controller.start(principal, project, key, body).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, controller.start(principal, project, key, body).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.start(principal, project, key, body).getStatusCode());
    }

    @Test
    void malformedUuidAndNonObjectInputsCannotCallRpc() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.list(principal, "bad").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.start(principal, project, key,
                new DagRunController.StartBody(workflow, 1, "[1]")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.detail(principal, project, "bad").getStatusCode());
        verifyNoInteractions(rpc);
    }

    private DagStartReply reply(String code) {
        return new DagStartReply(code, new DagRunView(UUID.randomUUID().toString(), project,
                workflow, 1, "a".repeat(64), "RUNNING", "2026-10-27T00:00:00Z",
                "2026-10-27T00:00:00Z"), false);
    }
}
