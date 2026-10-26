package com.peachsoft.otryx.flow.api.run;

import com.peachsoft.otryx.flow.contracts.v1.EngineRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import java.security.Principal;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅公开登录用户项目内的持久化逻辑 Run；当前状态不代表 Worker 实际执行。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class RunController {
    @OtryxRpcReference(version = "1.0.0")
    private EngineRpc engine;

    /** 通过 OTRYX RPC 查询 Engine 所拥有的记录，禁止在 API 中跨库查询。 */
    @GetMapping
    public ResponseEntity<?> list(Principal principal, @PathVariable String projectId) {
        if (!validId(projectId)) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_RUN_PROJECT"));
        }
        return ResponseEntity.ok(Arrays.asList(engine.listRuns(principal.getName(), projectId).runs()));
    }

    private static boolean validId(String id) {
        if (id == null) return false;
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }
}
