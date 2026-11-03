package com.peachsoft.otryx.flow.api.admission;

import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.ProjectAdmissionCommand;
import com.peachsoft.otryx.flow.contracts.v1.ProjectAdmissionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkerDrainCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkerDrainView;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目配额与 Worker 停领的 REST 入口。
 *
 * <p>写入只允许 ADMIN。领取令牌不从这里返回。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@RestController
public class AdmissionController {
    private static final String RPC_VERSION = "1.0.0";
    @OtryxRpcReference(version = RPC_VERSION)
    private DispatcherRpc dispatcher;

    /** 读取项目配额。错误的项目标识返回 400，不查询其他所有者。 */
    @GetMapping("/api/v1/projects/{projectId}/admission")
    public ResponseEntity<?> get(Principal principal, @PathVariable String projectId) {
        if (!validId(projectId)) return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PROJECT_ID"));
        return ResponseEntity.ok(dispatcher.getAdmission(principal.getName(), projectId));
    }

    /** 保存项目配额。范围不合法时返回 400。 */
    @PutMapping("/api/v1/projects/{projectId}/admission")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> put(Principal principal, @PathVariable String projectId,
            @Valid @RequestBody AdmissionBody body) {
        if (!validId(projectId)) return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PROJECT_ID"));
        try {
            ProjectAdmissionView saved = dispatcher.putAdmission(new ProjectAdmissionCommand(
                    principal.getName(), projectId, body.maxInflight(), body.maxQueued(),
                    body.concurrencyPolicy(), body.retentionDays()));
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_ADMISSION"));
        }
    }

    /** 列出尚未到期的 Worker 停领记录。没有记录时返回空数组，不编造心跳。 */
    @GetMapping("/api/v1/workers/drains")
    public List<WorkerDrainView> drains() {
        return Arrays.asList(dispatcher.activeDrains());
    }

    /** 让指定 Worker 停止领取新任务。已有租约不在这里撤销。 */
    @PostMapping("/api/v1/workers/drains")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> drain(Principal principal, @Valid @RequestBody DrainBody body) {
        try {
            return ResponseEntity.ok(dispatcher.drainWorker(
                    new WorkerDrainCommand(principal.getName(), body.workerId(), body.drainSeconds())));
        } catch (IllegalArgumentException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_DRAIN"));
        }
    }

    private static boolean validId(String id) {
        try {
            return id != null && UUID.fromString(id).toString().equals(id);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /**
     * 配额更新请求。
     *
     * @Author Ryan-Guizhou
     * @Version 0.1.0-SNAPSHOT
     * @CreateTime 2026/11/03 11:20
     */
    public record AdmissionBody(
            @Min(1) @Max(256) int maxInflight,
            @Min(1) @Max(1024) int maxQueued,
            @NotBlank @Pattern(regexp = "ALLOW|FORBID|QUEUE") String concurrencyPolicy,
            @Min(1) @Max(3650) int retentionDays) { }

    /**
     * Worker 停领请求。
     *
     * @Author Ryan-Guizhou
     * @Version 0.1.0-SNAPSHOT
     * @CreateTime 2026/11/03 11:20
     */
    public record DrainBody(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{3,64}") String workerId,
            @Min(1) @Max(3600) int drainSeconds) { }
}
