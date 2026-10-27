package com.peachsoft.otryx.flow.engine.observability;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 以有界周期查询发布 Engine 的 DAG 与 Outbox 持久化状态指标。
 */
@Component
public final class EngineDatabaseMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineDatabaseMetrics.class);
    private static final String ACTIVE_RUNS = "flow.dag.run.active";
    private static final String BLOCKED_NODES = "flow.dag.node.blocked";
    private static final String PENDING_OUTBOX = "flow.dag.outbox.pending";
    private static final String OUTBOX_OLDEST_SECONDS = "flow.dag.outbox.oldest.seconds";
    private static final String RUNNING = "RUNNING";
    private static final String BLOCKED = "BLOCKED";
    private static final String PENDING = "PENDING";
    private static final String SQL_ACTIVE_RUNS = "SELECT COUNT(*) FROM flow_dag_run WHERE status = ?";
    private static final String SQL_BLOCKED_NODES = "SELECT COUNT(*) FROM flow_dag_node_run WHERE status = ?";
    private static final String SQL_PENDING_OUTBOX = "SELECT COUNT(*) FROM flow_dag_dispatch_outbox WHERE delivery_state = ?";
    private static final String SQL_OUTBOX_OLDEST = "SELECT COALESCE(GREATEST(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(3)) / 1000000.0, 0), 0) FROM flow_dag_dispatch_outbox WHERE delivery_state = ?";

    private final JdbcTemplate jdbc;
    private final AtomicLong activeRuns = new AtomicLong();
    private final AtomicLong blockedNodes = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong outboxOldestMillis = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();

    /** 注册指标；不在 Run/Node 状态转换事务内执行额外遥测操作。 */
    public EngineDatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder(ACTIVE_RUNS, activeRuns, AtomicLong::doubleValue).register(registry);
        Gauge.builder(BLOCKED_NODES, blockedNodes, AtomicLong::doubleValue).register(registry);
        Gauge.builder(PENDING_OUTBOX, pendingOutbox, AtomicLong::doubleValue).register(registry);
        Gauge.builder(OUTBOX_OLDEST_SECONDS, outboxOldestMillis, value -> value.doubleValue() / 1000.0).register(registry);
    }

    /** 刷新权威数据库状态；查询失败保留最近成功值且不传播到业务线程。 */
    @Scheduled(fixedDelayString = "${flow.observability.refresh-ms:15000}", initialDelayString = "${flow.observability.initial-delay-ms:5000}")
    public void refresh() {
        try {
            activeRuns.set(count(SQL_ACTIVE_RUNS, RUNNING));
            blockedNodes.set(count(SQL_BLOCKED_NODES, BLOCKED));
            pendingOutbox.set(count(SQL_PENDING_OUTBOX, PENDING));
            outboxOldestMillis.set(Math.max(0L, Math.round(decimal(SQL_OUTBOX_OLDEST, PENDING) * 1000.0)));
            failureReported.set(false);
        } catch (DataAccessException failure) {
            if (failureReported.compareAndSet(false, true)) {
                LOGGER.warn("Engine metrics refresh failed: errorType={}", failure.getClass().getSimpleName());
            }
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private double decimal(String sql, Object... args) {
        Double value = jdbc.queryForObject(sql, Double.class, args);
        return value == null ? 0.0 : value;
    }
}
