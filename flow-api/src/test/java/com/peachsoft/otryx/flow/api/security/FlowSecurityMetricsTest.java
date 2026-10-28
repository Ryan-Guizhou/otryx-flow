package com.peachsoft.otryx.flow.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * 验证安全事件计数器不包含动态标签。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/28 11:45
 */
class FlowSecurityMetricsTest {

    @Test
    void recordsSecurityEventsWithoutHighCardinalityTags() {
        try (SimpleMeterRegistry registry = new SimpleMeterRegistry()) {
            FlowSecurityMetrics metrics = new FlowSecurityMetrics(registry);
            metrics.recordLoginFailure();
            metrics.recordAuthenticationFailure();
            metrics.recordAuthorizationDenial();
            metrics.recordCsrfDenial();

            assertCounter(registry, "flow.security.login.failures");
            assertCounter(registry, "flow.security.authentication.failures");
            assertCounter(registry, "flow.security.authorization.denials");
            assertCounter(registry, "flow.security.csrf.denials");
        }
    }

    private static void assertCounter(SimpleMeterRegistry registry, String name) {
        var counter = registry.get(name).counter();
        assertEquals(1.0, counter.count());
        assertTrue(counter.getId().getTags().isEmpty());
    }
}
