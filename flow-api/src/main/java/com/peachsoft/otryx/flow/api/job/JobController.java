package com.peachsoft.otryx.flow.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.JobCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobDraftUpdateCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.JobPublishCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST facade for persisted job drafts and immutable revisions over OTRYX RPC. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/jobs")
public class JobController {
    private static final String RPC_VERSION = "1.0.0";
    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String REQUEST_KEY_PATTERN = "[a-zA-Z0-9_-]{16,80}";
    @OtryxRpcReference(version = RPC_VERSION)
    private JobRpc jobs;

    /** List owned jobs; missing projects do not reveal other owners' data. */
    @GetMapping
    public ResponseEntity<?> list(Principal principal, @PathVariable String projectId) {
        if (!validId(projectId)) return invalidId();
        return ResponseEntity.ok(Arrays.asList(jobs.list(principal.getName(), projectId).jobs()));
    }

    /** Create a draft with a durable request key. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(Principal principal, @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody DraftBody body) {
        if (!validId(projectId) || !validKey(key) || !validJson(body.parametersJson())) return badRequest();
        JobMutationReply result = jobs.createDraft(new JobCreateDraftCommand(principal.getName(),
                projectId, key, body.name(), body.handlerKey(), body.parametersJson(),
                body.timeoutSeconds(), body.maxAttempts()));
        return respond(result, result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    /** CAS-update a draft without changing previous published snapshots. */
    @PutMapping("/{jobId}/draft")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> save(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @Valid @RequestBody UpdateBody body) {
        if (!validId(projectId) || !validId(jobId) || !validJson(body.parametersJson())) return badRequest();
        JobMutationReply result = jobs.saveDraft(new JobDraftUpdateCommand(principal.getName(),
                projectId, jobId, body.expectedDraftVersion(), body.name(), body.handlerKey(),
                body.parametersJson(), body.timeoutSeconds(), body.maxAttempts()));
        return respond(result, HttpStatus.OK);
    }

    /** Publish a permanent revision once for each stable publication key. */
    @PostMapping("/{jobId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> publish(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PublishBody body) {
        if (!validId(projectId) || !validId(jobId) || !validKey(key)) return badRequest();
        return respond(jobs.publish(new JobPublishCommand(principal.getName(), projectId,
                jobId, body.expectedDraftVersion(), key)), HttpStatus.OK);
    }

    /** Fetch a published, immutable snapshot rather than a mutable draft. */
    @GetMapping("/{jobId}/revisions/{revision}")
    public ResponseEntity<?> revision(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @PathVariable int revision) {
        if (!validId(projectId) || !validId(jobId) || revision <= 0) return badRequest();
        var snapshot = jobs.revision(principal.getName(), projectId, jobId, revision);
        return snapshot == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(snapshot);
    }

    private static ResponseEntity<?> respond(JobMutationReply result, HttpStatus createdStatus) {
        return switch (result.code()) {
            case "OK" -> ResponseEntity.status(createdStatus).body(result.job());
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "JOB_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "JOB_VERSION_CONFLICT"));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("code", "JOB_RPC_ERROR"));
        };
    }

    private static boolean validJson(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) return false;
        try {
            var node = JSON_MAPPER.readTree(value);
            return node != null && node.isObject();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    private static boolean validKey(String key) {
        return key != null && key.matches(REQUEST_KEY_PATTERN);
    }

    private static boolean validId(String id) {
        if (id == null) return false;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static ResponseEntity<?> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JOB_REQUEST"));
    }

    private static ResponseEntity<?> invalidId() { return badRequest(); }

    /** Validated draft creation/edit payload. */
    public record DraftBody(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,127}") String handlerKey,
            @NotBlank @Size(max = 16384) String parametersJson,
            @Min(1) @Max(86400) int timeoutSeconds,
            @Min(1) @Max(10) int maxAttempts) { }

    /** Optimistic draft edit with the version observed by the browser. */
    public record UpdateBody(
            @Min(1) long expectedDraftVersion,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Pattern(regexp = "[a-z][a-z0-9_.-]{2,127}") String handlerKey,
            @NotBlank @Size(max = 16384) String parametersJson,
            @Min(1) @Max(86400) int timeoutSeconds,
            @Min(1) @Max(10) int maxAttempts) { }

    /** Version read at publish time; stale publication must return 409. */
    public record PublishBody(@Min(1) long expectedDraftVersion) { }
}
