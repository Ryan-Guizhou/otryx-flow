package com.peachsoft.otryx.flow.dispatcher.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dispatcher 队列与租约指标测试。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 18:38
 */
class DispatcherDatabaseMetricsTest {
    @Test
    void refreshesQueueAndLeaseMetrics() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(11L, 4L, 1L, 6L);
        DispatcherDatabaseMetrics metrics = new DispatcherDatabaseMetrics(jdbc, registry);

        metrics.refresh();

        assertEquals(11.0, registry.find("flow.dispatch.queue.ready").gauge().value());
        assertEquals(4.0, registry.find("flow.dispatch.lease.active").gauge().value());
        assertEquals(1.0, registry.find("flow.dispatch.lease.expired").gauge().value());
        assertEquals(6.0, registry.find("flow.dispatch.outbox.pending").gauge().value());
    }
}
