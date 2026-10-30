package com.peachsoft.otryx.flow.scheduler.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Scheduler 持久化指标和遥测故障隔离测试。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 18:38
 */
class SchedulerDatabaseMetricsTest {
    @Test
    void refreshesPersistedScheduleMetrics() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(4L, 2L, 3L);
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class))).thenReturn(1.25);
        SchedulerDatabaseMetrics metrics = new SchedulerDatabaseMetrics(jdbc, registry);

        metrics.refresh();
        assertEquals(1.0, registry.find("flow.database.metrics.refresh.state").gauge().value());

        assertEquals(4.0, registry.find("flow.schedule.enabled").gauge().value());
        assertEquals(2.0, registry.find("flow.schedule.trigger.pending").gauge().value());
        assertEquals(3.0, registry.find("flow.schedule.outbox.pending").gauge().value());
        assertEquals(1.25, registry.find("flow.schedule.due_lag.seconds").gauge().value());
    }

    @Test
    void databaseFailureDoesNotEscapeMetricsRefresh() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        SchedulerDatabaseMetrics metrics = new SchedulerDatabaseMetrics(jdbc, registry);

        metrics.refresh();

        assertEquals(0.0, registry.find("flow.schedule.enabled").gauge().value());
        assertEquals(2.0, registry.find("flow.database.metrics.refresh.state").gauge().value());
    }
    @Test
    void recoversRefreshHealthAfterTemporaryJdbcFailure() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("offline"))
                .thenReturn(4L, 2L, 3L);
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class))).thenReturn(1.25);
        SchedulerDatabaseMetrics metrics = new SchedulerDatabaseMetrics(jdbc, registry);
        metrics.refresh();
        assertEquals(2.0, registry.find("flow.database.metrics.refresh.state").gauge().value());
        metrics.refresh();
        assertEquals(1.0, registry.find("flow.database.metrics.refresh.state").gauge().value());
        assertEquals(4.0, registry.find("flow.schedule.enabled").gauge().value());
    }
}
