package com.peachsoft.otryx.flow.api.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowPublishCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowRpc;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowSaveDraftCommand;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

/** 工作流定义 HTTP Facade，业务数据库读写只通过 OTRYX RPC。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflows")
public class WorkflowController {
    private static final String RPC_VERSION = "1.0.0";
    private static final int MAX_GRAPH_BYTES = 48 * 1024;
    private static final String KEY_PATTERN = "[a-zA-Z0-9_-]{16,80}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @OtryxRpcReference(version = RPC_VERSION)
    private WorkflowRpc workflows;

    /** 只返回登录所有者可见的项目内工作流。 */
    @GetMapping
    public ResponseEntity<?> list(Principal principal, @PathVariable String projectId) {
        if (!validId(projectId)) return badRequest();
        return ResponseEntity.ok(Arrays.asList(workflows.list(principal.getName(), projectId).workflows()));
    }

    /** 使用同一个幂等键重复提交相同内容最多生成一个草稿。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(Principal principal, @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody DraftBody body) {
        if (!validId(projectId) || !validKey(key) || !validGraphJson(body.graphJson())) return badRequest();
        WorkflowMutationReply outcome = workflows.createDraft(new WorkflowCreateDraftCommand(
                principal.getName(), projectId, key, body.name(), body.graphJson()));
        return respond(outcome, outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    /** 利用版本 CAS 保存草稿，禁止透明重试导致覆盖。 */
    @PutMapping("/{workflowId}/draft")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> save(Principal principal, @PathVariable String projectId,
            @PathVariable String workflowId, @Valid @RequestBody UpdateBody body) {
        if (!validId(projectId) || !validId(workflowId)
                || body.expectedDraftVersion() < 1 || !validGraphJson(body.graphJson())) return badRequest();
        return respond(workflows.saveDraft(new WorkflowSaveDraftCommand(
                principal.getName(), projectId, workflowId, body.expectedDraftVersion(),
                body.name(), body.graphJson())), HttpStatus.OK);
    }

    /** 发布有 SHA-256 的固定 DAG 快照，不触发任务执行。 */
    @PostMapping("/{workflowId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> publish(Principal principal, @PathVariable String projectId,
            @PathVariable String workflowId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PublishBody body) {
        if (!validId(projectId) || !validId(workflowId) || !validKey(key)
                || body.expectedDraftVersion() < 1) return badRequest();
        return respond(workflows.publish(new WorkflowPublishCommand(
                principal.getName(), projectId, workflowId,
                body.expectedDraftVersion(), key)), HttpStatus.OK);
    }

    /** 读取不可变版本，外部用户无法枚举其他项目数据。 */
    @GetMapping("/{workflowId}/revisions/{revision}")
    public ResponseEntity<?> revision(Principal principal, @PathVariable String projectId,
            @PathVariable String workflowId, @PathVariable int revision) {
        if (!validId(projectId) || !validId(workflowId) || revision < 1) return badRequest();
        var snapshot = workflows.revision(principal.getName(), projectId, workflowId, revision);
        return snapshot == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(snapshot);
    }

    private static ResponseEntity<?> respond(WorkflowMutationReply result, HttpStatus success) {
        return switch (result.code()) {
            case "OK" -> ResponseEntity.status(success).body(result.workflow());
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "WORKFLOW_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "WORKFLOW_VERSION_CONFLICT"));
            case "INVALID" -> badRequest();
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("code", "WORKFLOW_RPC_ERROR"));
        };
    }

    private static boolean validGraphJson(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_GRAPH_BYTES) return false;
        try {
            var root = JSON.readTree(json);
            return root != null && root.isObject() && root.path("nodes").isArray()
                    && root.path("edges").isArray();
        } catch (JsonProcessingException error) {
            return false;
        }
    }

    private static boolean validKey(String value) {
        return value != null && value.matches(KEY_PATTERN);
    }

    private static boolean validId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static ResponseEntity<?> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_WORKFLOW_REQUEST"));
    }

    /** 最大 48 KiB 字节的结构化 DAG 草稿。 */
    public record DraftBody(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 49152) String graphJson) { }

    /** CAS 编辑必须携带已读取的版本。 */
    public record UpdateBody(
            @Min(1) long expectedDraftVersion,
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 49152) String graphJson) { }

    /** 发布前端观察到的完整草稿版本。 */
    public record PublishBody(@Min(1) long expectedDraftVersion) { }
}

