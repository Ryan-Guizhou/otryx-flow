package com.peachsoft.otryx.flow.api.project;

import com.peachsoft.otryx.flow.contracts.v1.ProjectCreateCommand;
import com.peachsoft.otryx.flow.contracts.v1.ProjectCreatedReply;
import com.peachsoft.otryx.flow.contracts.v1.ProjectRpc;
import com.peachsoft.otryx.flow.contracts.v1.ProjectView;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 项目 HTTP 接口，通过 RPC 访问元数据服务。 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private static final String VERSION = "1.0.0";
    @OtryxRpcReference(version = VERSION)
    private ProjectRpc projectRpc;

    /** 根据登录用户查询项目。 */
    @GetMapping
    public List<ProjectView> list(Principal principal) {
        return Arrays.asList(projectRpc.listByOwner(principal.getName()).projects());
    }

    /** 使用客户端提供的幂等键创建项目。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(Principal principal,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody NewProject body) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{16,80}")) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_IDEMPOTENCY_KEY"));
        }
        ProjectCreatedReply outcome = projectRpc.create(
                new ProjectCreateCommand(principal.getName(), key, body.name()));
        if (outcome.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "IDEMPOTENCY_CONFLICT"));
        }
        return ResponseEntity.status(outcome.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.project());
    }

    /** 经过参数校验的项目创建请求。 */
    public record NewProject(@NotBlank @Size(max = 100) String name) { }
}
