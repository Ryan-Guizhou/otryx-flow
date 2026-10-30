package com.peachsoft.otryx.flow.engine.observability;

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
 * Engine DAG 与 Outbox 指标测试。
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 18:38
 */
class EngineDatabaseMetricsTest {
    @Test
    void refreshesPersistedDagMetrics() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(5L, 7L, 2L);
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class))).thenReturn(3.5);
        EngineDatabaseMetrics metrics = new EngineDatabaseMetrics(jdbc, registry);

        metrics.refresh();
        assertEquals(1.0, registry.find("flow.database.metrics.refresh.state").gauge().value());

        assertEquals(5.0, registry.find("flow.dag.run.active").gauge().value());
        assertEquals(7.0, registry.find("flow.dag.node.blocked").gauge().value());
        assertEquals(2.0, registry.find("flow.dag.outbox.pending").gauge().value());
        assertEquals(3.5, registry.find("flow.dag.outbox.oldest.seconds").gauge().value());
    }
    @Test
    void preservesPreviousDatabaseSnapshotOnRefreshFailure() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(5L, 7L, 2L).thenThrow(new DataAccessResourceFailureException("offline"));
        when(jdbc.queryForObject(anyString(), eq(Double.class), any(Object[].class))).thenReturn(3.5);
        EngineDatabaseMetrics metrics = new EngineDatabaseMetrics(jdbc, registry);
        metrics.refresh();
        assertEquals(1.0, registry.find("flow.database.metrics.refresh.state").gauge().value());
        metrics.refresh();
        assertEquals(2.0, registry.find("flow.database.metrics.refresh.state").gauge().value());
        assertEquals(5.0, registry.find("flow.dag.run.active").gauge().value());
    }
}
