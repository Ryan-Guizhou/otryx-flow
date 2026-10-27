package com.peachsoft.otryx.flow.dispatcher.observability;

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
 * 以有界周期查询发布 Dispatcher 队列、租约和结果 Outbox 指标。
 */
@Component
public final class DispatcherDatabaseMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatcherDatabaseMetrics.class);
    private static final String READY_TASKS = "flow.dispatch.queue.ready";
    private static final String ACTIVE_LEASES = "flow.dispatch.lease.active";
    private static final String EXPIRED_LEASES = "flow.dispatch.lease.expired";
    private static final String PENDING_OUTBOX = "flow.dispatch.outbox.pending";
    private static final String READY = "READY";
    private static final String LEASED = "LEASED";
    private static final String PENDING = "PENDING";
    private static final String SQL_READY = "SELECT COUNT(*) FROM flow_dispatch_task WHERE status = ?";
    private static final String SQL_ACTIVE_LEASES = "SELECT COUNT(*) FROM flow_dispatch_task WHERE status = ?";
    private static final String SQL_EXPIRED_LEASES = "SELECT COUNT(*) FROM flow_dispatch_task WHERE status = ? AND lease_until <= UTC_TIMESTAMP(3)";
    private static final String SQL_PENDING_OUTBOX = "SELECT COUNT(*) FROM flow_dispatch_outcome_outbox WHERE delivery_state = ?";

    private final JdbcTemplate jdbc;
    private final AtomicLong readyTasks = new AtomicLong();
    private final AtomicLong activeLeases = new AtomicLong();
    private final AtomicLong expiredLeases = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();

    /** 注册低基数指标并绑定 Dispatcher 自有 DataSource。 */
    public DispatcherDatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder(READY_TASKS, readyTasks, AtomicLong::doubleValue).register(registry);
        Gauge.builder(ACTIVE_LEASES, activeLeases, AtomicLong::doubleValue).register(registry);
        Gauge.builder(EXPIRED_LEASES, expiredLeases, AtomicLong::doubleValue).register(registry);
        Gauge.builder(PENDING_OUTBOX, pendingOutbox, AtomicLong::doubleValue).register(registry);
    }

    /** 周期刷新队列快照；查询失败不会影响领取、心跳或完成事务。 */
    @Scheduled(fixedDelayString = "${flow.observability.refresh-ms:15000}", initialDelayString = "${flow.observability.initial-delay-ms:5000}")
    public void refresh() {
        try {
            readyTasks.set(count(SQL_READY, READY));
            activeLeases.set(count(SQL_ACTIVE_LEASES, LEASED));
            expiredLeases.set(count(SQL_EXPIRED_LEASES, LEASED));
            pendingOutbox.set(count(SQL_PENDING_OUTBOX, PENDING));
            failureReported.set(false);
        } catch (DataAccessException failure) {
            if (failureReported.compareAndSet(false, true)) {
                LOGGER.warn("Dispatcher metrics refresh failed: errorType={}", failure.getClass().getSimpleName());
            }
        }
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }
}
