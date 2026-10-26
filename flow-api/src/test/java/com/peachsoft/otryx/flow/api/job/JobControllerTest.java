package com.peachsoft.otryx.flow.api.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.peachsoft.otryx.flow.contracts.v1.JobMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobView;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

/** Verify REST status boundaries for job mutations. */
class JobControllerTest {
    private final JobController controller = new JobController();
    private final JobRpc rpc = mock(JobRpc.class);
    private final Principal owner = () -> "admin";
    private final String project = UUID.randomUUID().toString();
    private final String job = UUID.randomUUID().toString();
    private final String key = UUID.randomUUID().toString();

    @BeforeEach
    void prepare() { ReflectionTestUtils.setField(controller, "jobs", rpc); }

    @Test
    void createAndReplayStatuses() {
        when(rpc.createDraft(any())).thenReturn(reply("OK", false), reply("OK", true));
        var body = new JobController.DraftBody("First task", "safe.echo", "{}", 20, 2);
        assertEquals(HttpStatus.CREATED, controller.create(owner, project, key, body).getStatusCode());
        assertEquals(HttpStatus.OK, controller.create(owner, project, key, body).getStatusCode());
    }

    @Test
    void conflictingVersionAndWrongOwnerAreNotSuccess() {
        when(rpc.saveDraft(any())).thenReturn(reply("CONFLICT", false), reply("NOT_FOUND", false));
        var body = new JobController.UpdateBody(1, "Updated", "safe.echo", "{}", 20, 2);
        assertEquals(HttpStatus.CONFLICT, controller.save(owner, project, job, body).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.save(owner, project, job, body).getStatusCode());
    }

    @Test
    void invalidIdentifiersRejectedBeforeRemoteCall() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.list(owner, "../bad").getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.publish(owner, project, "invalid", key, new JobController.PublishBody(1)).getStatusCode());
        verifyNoInteractions(rpc);
    }

    @Test
    void rejectNonObjectJsonBeforeRpc() {
        var invalid = new JobController.DraftBody("Bad", "safe.echo", "[1,2]", 10, 1);
        assertEquals(HttpStatus.BAD_REQUEST, controller.create(owner, project, key, invalid).getStatusCode());
        verifyNoInteractions(rpc);
    }

    private JobMutationReply reply(String code, boolean duplicate) {
        return new JobMutationReply(code, new JobView(job, project, "Task", "safe.echo", "{}",
                20, 2, 1, 0, 0, "2026-10-26T00:00:00Z"), duplicate);
    }
}
