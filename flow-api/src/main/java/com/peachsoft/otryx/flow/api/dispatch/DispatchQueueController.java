package com.peachsoft.otryx.flow.api.dispatch;

import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
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

/** 仅只读的 Dispatcher 队列信息入口；Worker 租约令牌绝不暴露至浏览器。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/dispatch-tasks")
public class DispatchQueueController {
    @OtryxRpcReference(version = "1.0.0")
    private DispatcherRpc dispatcher;

    /** 按当前登录用户和项目隔离查询；不提供浏览器领取/完成接口。 */
    @GetMapping
    public ResponseEntity<?> list(Principal user, @PathVariable String projectId) {
        try {
            if (!UUID.fromString(projectId).toString().equals(projectId)) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_PROJECT_ID"));
        }
        return ResponseEntity.ok(Arrays.asList(dispatcher.list(user.getName(), projectId).tasks()));
    }
}
