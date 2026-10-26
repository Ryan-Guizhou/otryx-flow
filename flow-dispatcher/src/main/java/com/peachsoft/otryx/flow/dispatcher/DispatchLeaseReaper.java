package com.peachsoft.otryx.flow.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅清理耗尽最大尝试数的过期租约；仍可重试的任务由新 Worker 重新领取。 */
@Component
public class DispatchLeaseReaper {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchLeaseReaper.class);
    private final DispatcherRpcService dispatcher;

    /** 注入 Dispatcher 队列服务，复用相同的 CAS 和事务规则。 */
    public DispatchLeaseReaper(DispatcherRpcService dispatcher) { this.dispatcher = dispatcher; }

    /** 固定延迟清理，故障会在下轮继续尝试。 */
    @Scheduled(fixedDelayString = "${flow.dispatcher.reaper-poll-ms:1000}")
    public void poll() {
        try {
            int expired = dispatcher.expireExhaustedOnce();
            if (expired > 0) LOGGER.info("Finalized {} exhausted dispatch leases", expired);
        } catch (RuntimeException problem) {
            LOGGER.warn("Dispatch lease reaper failed: {}", problem.getClass().getSimpleName());
        }
    }
}
