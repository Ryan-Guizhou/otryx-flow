package com.peachsoft.otryx.flow.api.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.api.audit.FlowAuditRecorder;
import com.peachsoft.otryx.flow.contracts.v1.JobCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobDraftUpdateCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.JobPublishCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobView;
import com.peachsoft.otryx.flow.contracts.v1.ProjectRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleRpc;
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
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * 任务草稿与不可变版本的 REST 入口。
 *
 * <p>Handler 标识必须落在 safe. 白名单内。变更成功后写入审计；审计器缺失时单元测试仍可映射状态码。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/jobs")
public class JobController {
    private static final String RPC_VERSION = "1.0.0";
    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String REQUEST_KEY_PATTERN = "[a-zA-Z0-9_-]{16,80}";
    @OtryxRpcReference(version = RPC_VERSION)
    private JobRpc jobs;
    @OtryxRpcReference(version = RPC_VERSION)
    private ProjectRpc projects;
    @OtryxRpcReference(version = RPC_VERSION)
    private ScheduleRpc schedules;
    @Autowired(required = false)
    private FlowAuditRecorder audit;

    /** List owned jobs; missing projects do not reveal other owners' data. */
    @GetMapping
    public ResponseEntity<?> list(Principal principal, @PathVariable String projectId) {
        if (!validId(projectId)) return invalidId();
        if (!allowed(principal, projectId)) return notFound();
        return ResponseEntity.ok(Arrays.asList(jobs.list(principal.getName(), projectId).jobs()));
    }

    /** 查询一条任务。归档后仍可按标识读取，列表不再展示它。 */
    @GetMapping("/{jobId}")
    public ResponseEntity<?> get(Principal principal, @PathVariable String projectId, @PathVariable String jobId) {
        if (!validId(projectId) || !validId(jobId)) return badRequest();
        if (!allowed(principal, projectId)) return notFound();
        JobView job = jobs.get(principal.getName(), projectId, jobId);
        return job == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_NOT_FOUND"))
                : ResponseEntity.ok(job);
    }

    /** Create a draft with a durable request key. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> create(Principal principal, @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody DraftBody body) {
        if (!validId(projectId) || !validKey(key) || !validJson(body.parametersJson())) return badRequest();
        if (!allowed(principal, projectId)) return notFound();
        if (!HandlerPolicy.allowed(body.handlerKey(), body.parametersJson())) return rejectedHandler();
        JobMutationReply result = jobs.createDraft(new JobCreateDraftCommand(principal.getName(),
                projectId, key, body.name(), body.handlerKey(), body.parametersJson(),
                body.timeoutSeconds(), body.maxAttempts()));
        return respond(principal.getName(), result, result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    /** CAS-update a draft without changing previous published snapshots. */
    @PutMapping("/{jobId}/draft")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> save(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @Valid @RequestBody UpdateBody body) {
        if (!validId(projectId) || !validId(jobId) || !validJson(body.parametersJson())) return badRequest();
        if (!allowed(principal, projectId)) return notFound();
        if (!HandlerPolicy.allowed(body.handlerKey(), body.parametersJson())) return rejectedHandler();
        JobMutationReply result = jobs.saveDraft(new JobDraftUpdateCommand(principal.getName(),
                projectId, jobId, body.expectedDraftVersion(), body.name(), body.handlerKey(),
                body.parametersJson(), body.timeoutSeconds(), body.maxAttempts()));
        return respond(principal.getName(), result, HttpStatus.OK);
    }

    /** Publish a permanent revision once for each stable publication key. */
    @PostMapping("/{jobId}/publish")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> publish(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PublishBody body) {
        if (!validId(projectId) || !validId(jobId) || !validKey(key)) return badRequest();
        if (!allowed(principal, projectId)) return notFound();
        return respond(principal.getName(), jobs.publish(new JobPublishCommand(principal.getName(), projectId,
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

    /** 归档任务。历史执行仍保留。 */
    @PostMapping("/{jobId}/archive")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> archive(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @Valid @RequestBody VersionBody body) {
        if (!validId(projectId) || !validId(jobId)) return badRequest();
        if (!allowed(principal, projectId)) return notFound();
        if (schedules != null && Arrays.stream(schedules.list(principal.getName(), projectId).schedules())
                .anyMatch(item -> jobId.equals(item.jobId()) && item.enabled())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SCHEDULE_STILL_ACTIVE"));
        }
        return respond(principal.getName(), jobs.archive(principal.getName(), projectId, jobId,
                body.expectedDraftVersion()), HttpStatus.OK);
    }

    /** 启用或停用任务定义。不停掉已经存在的 Run。 */
    @PostMapping("/{jobId}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> enabled(Principal principal, @PathVariable String projectId,
            @PathVariable String jobId, @Valid @RequestBody EnabledBody body) {
        if (!validId(projectId) || !validId(jobId)) return badRequest();
        return respond(principal.getName(), jobs.setEnabled(principal.getName(), projectId, jobId,
                body.enabled(), body.expectedDraftVersion()), HttpStatus.OK);
    }

    private ResponseEntity<?> respond(String actor, JobMutationReply result, HttpStatus createdStatus) {
        if ("OK".equals(result.code()) && audit != null && result.job() != null) {
            audit.record(actor, "JOB_MUTATION", result.job().projectId(), result.code());
        }
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

    private boolean allowed(Principal principal, String projectId) {
        return projects == null || projects.hasAccess(principal.getName(), projectId);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "PROJECT_NOT_FOUND"));
    }

    private static ResponseEntity<?> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_JOB_REQUEST"));
    }

    private static ResponseEntity<?> rejectedHandler() {
        return ResponseEntity.badRequest().body(Map.of("code", "HANDLER_REJECTED"));
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
    /** 归档或启停时浏览器看到的草稿版本。 */
    public record VersionBody(@Min(1) long expectedDraftVersion) { }
    /** 停用后新的手动执行会被拒绝。 */
    public record EnabledBody(boolean enabled, @Min(1) long expectedDraftVersion) { }
}
