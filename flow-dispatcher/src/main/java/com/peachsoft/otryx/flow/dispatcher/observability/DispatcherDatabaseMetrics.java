package com.peachsoft.otryx.flow.dispatcher.observability;

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
 * 周期读取 Dispatcher 自有数据库中的队列、租约和结果 Outbox 状态。
 *
 * <p>Gauge 使用持久化状态快照；查询故障不会阻塞领取、心跳或完成事务。</p>
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 18:38
 */
@Component
public final class DispatcherDatabaseMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(DispatcherDatabaseMetrics.class);
    private static final String DATABASE_REFRESH_STATE = "flow.database.metrics.refresh.state";
    private static final int REFRESH_HEALTHY = 1;
    private static final int REFRESH_FAILED = 2;

    private static final String READY_TASKS = "flow.dispatch.queue.ready";
    private static final String ACTIVE_LEASES = "flow.dispatch.lease.active";
    private static final String EXPIRED_LEASES = "flow.dispatch.lease.expired";
    private static final String PENDING_OUTBOX = "flow.dispatch.outbox.pending";

    private static final String READY = "READY";
    private static final String LEASED = "LEASED";
    private static final String PENDING = "PENDING";
    private static final String SQL_READY =
            "SELECT COUNT(*) FROM flow_dispatch_task WHERE status = ?";
    private static final String SQL_ACTIVE_LEASES =
            "SELECT COUNT(*) FROM flow_dispatch_task WHERE status = ?";
    private static final String SQL_EXPIRED_LEASES =
            "SELECT COUNT(*) FROM flow_dispatch_task WHERE status = ? "
                    + "AND lease_until <= UTC_TIMESTAMP(3)";
    private static final String SQL_PENDING_OUTBOX =
            "SELECT COUNT(*) FROM flow_dispatch_outcome_outbox WHERE delivery_state = ?";

    private final JdbcTemplate jdbc;
    private final AtomicLong readyTasks = new AtomicLong();
    private final AtomicLong activeLeases = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicInteger refreshState = new AtomicInteger();

    /**
     * 注册 Dispatcher 队列 Gauge，并绑定 Dispatcher 自有数据库查询。
     *
     * @param jdbc Dispatcher 自有数据源的 JDBC 操作入口
     * @param registry 暴露指标的注册器
     */
    public DispatcherDatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder(DATABASE_REFRESH_STATE, refreshState, AtomicInteger::get)
                .description("Database metrics refresh: 0=unknown, 1=healthy, 2=failed")
                .register(registry);
        Gauge.builder(READY_TASKS, readyTasks, AtomicLong::doubleValue)
                .description("Number of dispatch tasks ready to be claimed")
                .register(registry);
        Gauge.builder(ACTIVE_LEASES, activeLeases, AtomicLong::doubleValue)
                .description("Number of dispatch tasks with an active lease")
                .register(registry);
        Gauge.builder(EXPIRED_LEASES, expiredLeases, AtomicLong::doubleValue)
                .description("Number of leased dispatch tasks past their lease deadline")
                .register(registry);
        Gauge.builder(PENDING_OUTBOX, pendingOutbox, AtomicLong::doubleValue)
                .description("Number of pending Dispatcher outcome outbox records")
                .register(registry);
    }

    /**
     * 周期刷新队列快照；连续查询失败只记录一次 WARN，并保留最近一次成功值。
     */
    @Scheduled(
            fixedDelayString = "${flow.observability.refresh-ms:15000}",
            initialDelayString = "${flow.observability.initial-delay-ms:5000}")
    public void refresh() {
        try {
            long nextReadyTasks = count(SQL_READY, READY);
            long nextActiveLeases = count(SQL_ACTIVE_LEASES, LEASED);
            long nextExpiredLeases = count(SQL_EXPIRED_LEASES, LEASED);
            long nextPendingOutbox = count(SQL_PENDING_OUTBOX, PENDING);

            readyTasks.set(nextReadyTasks);
            activeLeases.set(nextActiveLeases);
            expiredLeases.set(nextExpiredLeases);
            pendingOutbox.set(nextPendingOutbox);
            refreshState.set(REFRESH_HEALTHY);
            failureReported.set(false);
        } catch (DataAccessException failure) {
            refreshState.set(REFRESH_FAILED);
            if (failureReported.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Dispatcher metrics refresh failed: errorType={}",
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
}
