package com.peachsoft.otryx.flow.api.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.DagRpc;
import com.peachsoft.otryx.flow.contracts.v1.DagStartCommand;
import com.peachsoft.otryx.flow.contracts.v1.DagStartReply;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户授权的 DAG Run 手动触发与节点级进度查询；不暴露 RPC 租约 Token。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/workflow-runs")
public class DagRunController {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_INPUT = 16 * 1024;
    private static final String KEY_PATTERN = "[a-zA-Z0-9_-]{16,80}";

    @OtryxRpcReference(version = "1.0.0")
    private DagRpc dag;

    /** 手动触发固定工作流版本，而不是可变草稿。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> start(Principal principal, @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String requestKey,
            @Valid @RequestBody StartBody body) {
        if (!validId(projectId) || !validId(body.workflowId()) || body.workflowRevision() < 1
                || requestKey == null || !requestKey.matches(KEY_PATTERN) || !validInput(body.inputJson())) {
            return invalid();
        }
        DagStartReply result = dag.start(new DagStartCommand(
                principal.getName(), projectId, body.workflowId(), body.workflowRevision(),
                requestKey, body.inputJson()));
        return switch (result.code()) {
            case "CREATED" -> ResponseEntity.status(HttpStatus.CREATED).body(result.run());
            case "DUPLICATE" -> ResponseEntity.ok(result.run());
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "WORKFLOW_REVISION_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "WORKFLOW_RUN_IDEMPOTENCY_CONFLICT"));
            case "INVALID" -> invalid();
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("code", "DAG_RPC_ERROR"));
        };
    }

    /** 登录用户只能读取归属项目的工作流实例。 */
    @GetMapping
    public ResponseEntity<?> list(Principal principal, @PathVariable String projectId) {
        if (!validId(projectId)) return invalid();
        return ResponseEntity.ok(Arrays.asList(dag.list(principal.getName(), projectId).runs()));
    }

    /** 返回 Engine 持久化节点状态，包括 SKIPPED 分支和条件决策。 */
    @GetMapping("/{runId}")
    public ResponseEntity<?> detail(Principal principal, @PathVariable String projectId,
            @PathVariable String runId) {
        if (!validId(projectId) || !validId(runId)) return invalid();
        var detail = dag.detail(principal.getName(), projectId, runId);
        return detail == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(detail);
    }

    private static boolean validId(String id) {
        if (id == null) return false;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean validInput(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT) return false;
        try {
            var node = JSON.readTree(json);
            return node != null && node.isObject();
        } catch (JsonProcessingException invalid) { return false; }
    }

    private static ResponseEntity<?> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_WORKFLOW_RUN"));
    }

    /** 严格固定版本，输入为大小受限的 JSON 对象字符串。 */
    public record StartBody(
            @NotBlank String workflowId,
            @Min(1) int workflowRevision,
            @NotBlank @Size(max = 16384) String inputJson) { }
}
