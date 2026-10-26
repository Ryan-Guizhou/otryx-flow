package com.peachsoft.otryx.flow.dispatcher;

import com.peachsoft.otryx.flow.contracts.v1.DispatchCompleteCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchHeartbeatCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseCommand;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅 GitHub Compose CI 的临时入口。默认 profile 完全不注册；严禁公网部署 e2e profile。
 * 此处仅使 CI 可使用真实 MySQL 验证租约代际，Worker SDK 将在 Issue #8 通过 RPC 直接调用。
 */
@Profile("e2e")
@RestController
@RequestMapping("/_e2e/dispatch")
public class DispatchE2eProbeController {
    private final DispatcherRpcService service;

    /** CI 注入真实 Dispatcher 服务和数据库连接。 */
    public DispatchE2eProbeController(DispatcherRpcService service) { this.service = service; }

    /** 仅临时 CI profile 提供的领取测试入口，非生产 API。 */
    @PostMapping("/claim")
    public Object claim(@RequestParam String workerId, @RequestParam String handlerKey) {
        return service.claim(new DispatchLeaseCommand(workerId, handlerKey, 1));
    }

    /** 仅 CI 用于验证过期 token 的续约拒绝。 */
    @PostMapping("/heartbeat")
    public Object heartbeat(@RequestParam String workerId, @RequestParam String taskId,
            @RequestParam long leaseEpoch, @RequestParam String leaseToken) {
        return service.heartbeat(new DispatchHeartbeatCommand(workerId, taskId, leaseEpoch, leaseToken));
    }

    /** 仅 CI 用于验证旧代际不能伪造新代际的完成结果。 */
    @PostMapping("/complete")
    public Object complete(@RequestParam String workerId, @RequestParam String taskId,
            @RequestParam long leaseEpoch, @RequestParam String leaseToken,
            @RequestParam String outcome) {
        return service.complete(new DispatchCompleteCommand(workerId, taskId, leaseEpoch, leaseToken, outcome));
    }
}
