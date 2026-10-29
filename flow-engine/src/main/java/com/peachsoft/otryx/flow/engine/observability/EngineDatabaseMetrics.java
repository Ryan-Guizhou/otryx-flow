package com.peachsoft.otryx.flow.engine.observability;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期读取 Engine 自有数据库中的 DAG 与 Outbox 状态，并发布低基数指标。
 *
 * <p>指标来自持久化状态快照，不在 Run/Node 状态转换事务中增加遥测写操作。</p>
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 18:38
 */
@Component
public final class EngineDatabaseMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(EngineDatabaseMetrics.class);
    private static final String DATABASE_REFRESH_STATE = "flow.database.metrics.refresh.state";
    private static final int REFRESH_HEALTHY = 1;
    private static final int REFRESH_FAILED = 2;

    private static final String ACTIVE_RUNS = "flow.dag.run.active";
    private static final String BLOCKED_NODES = "flow.dag.node.blocked";
    private static final String PENDING_OUTBOX = "flow.dag.outbox.pending";
    private static final String OUTBOX_OLDEST_SECONDS = "flow.dag.outbox.oldest.seconds";

    private static final String RUNNING = "RUNNING";
    private static final String BLOCKED = "BLOCKED";
    private static final String PENDING = "PENDING";
    private static final String SQL_ACTIVE_RUNS =
            "SELECT COUNT(*) FROM flow_dag_run WHERE status = ?";
    private static final String SQL_BLOCKED_NODES =
            "SELECT COUNT(*) FROM flow_dag_node_run WHERE status = ?";
    private static final String SQL_PENDING_OUTBOX =
            "SELECT COUNT(*) FROM flow_dag_dispatch_outbox WHERE delivery_state = ?";
    private static final String SQL_OUTBOX_OLDEST =
            "SELECT COALESCE(GREATEST(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), "
                    + "UTC_TIMESTAMP(3)) / 1000000.0, 0), 0) "
                    + "FROM flow_dag_dispatch_outbox WHERE delivery_state = ?";
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final JdbcTemplate jdbc;
    private final AtomicLong activeRuns = new AtomicLong();
    private final AtomicLong blockedNodes = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong outboxOldestMillis = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicInteger refreshState = new AtomicInteger();

    /**
     * 注册 Engine 状态 Gauge，并绑定 Engine 自有数据库查询。
     *
     * @param jdbc Engine 自有数据源的 JDBC 操作入口
     * @param registry 暴露指标的注册器
     */
    public EngineDatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder(DATABASE_REFRESH_STATE, refreshState, AtomicInteger::get)
                .description("Database metrics refresh: 0=unknown, 1=healthy, 2=failed")
                .register(registry);
        Gauge.builder(ACTIVE_RUNS, activeRuns, AtomicLong::doubleValue)
                .description("Number of persisted DAG runs in RUNNING state")
                .register(registry);
        Gauge.builder(BLOCKED_NODES, blockedNodes, AtomicLong::doubleValue)
                .description("Number of persisted DAG nodes in BLOCKED state")
                .register(registry);
        Gauge.builder(PENDING_OUTBOX, pendingOutbox, AtomicLong::doubleValue)
                .description("Number of pending Engine dispatch outbox records")
                .register(registry);
        Gauge.builder(
                        OUTBOX_OLDEST_SECONDS,
                        outboxOldestMillis,
                        value -> value.doubleValue() / MILLIS_PER_SECOND)
                .description("Age in seconds of the oldest pending Engine outbox record")
                .register(registry);
    }

    /**
     * 刷新权威数据库状态；查询失败时保留最近一次成功值，不向业务线程传播故障。
     */
    @Scheduled(
            fixedDelayString = "${flow.observability.refresh-ms:15000}",
            initialDelayString = "${flow.observability.initial-delay-ms:5000}")
    public void refresh() {
        try {
            long nextActiveRuns = count(SQL_ACTIVE_RUNS, RUNNING);
            long nextBlockedNodes = count(SQL_BLOCKED_NODES, BLOCKED);
            long nextPendingOutbox = count(SQL_PENDING_OUTBOX, PENDING);
            long nextOutboxOldestMillis =
                    Math.max(0L, Math.round(decimal(SQL_OUTBOX_OLDEST, PENDING) * MILLIS_PER_SECOND));

            activeRuns.set(nextActiveRuns);
            blockedNodes.set(nextBlockedNodes);
            pendingOutbox.set(nextPendingOutbox);
            outboxOldestMillis.set(nextOutboxOldestMillis);
            refreshState.set(REFRESH_HEALTHY);
            failureReported.set(false);
        } catch (DataAccessException failure) {
            refreshState.set(REFRESH_FAILED);
            if (failureReported.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Engine metrics refresh failed: errorType={}",
                        failure.getClass().getSimpleName());
            }
        }
    }

    /**
     * 执行返回行数的 SQL 查询，空结果按零处理。
     *
     * @param sql 已定义的 SQL 语句
     * @param args SQL 参数
     * @return 查询得到的行数
     */
    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /**
     * 执行数值 SQL 查询，空结果按零处理。
     *
     * @param sql 已定义的 SQL 语句
     * @param args SQL 参数
     * @return 查询得到的数值
     */
    private double decimal(String sql, Object... args) {
        Double value = jdbc.queryForObject(sql, Double.class, args);
        return value == null ? 0.0 : value;
    }
}
