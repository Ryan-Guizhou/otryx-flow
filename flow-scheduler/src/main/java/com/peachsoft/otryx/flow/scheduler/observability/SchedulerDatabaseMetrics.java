package com.peachsoft.otryx.flow.scheduler.observability;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;

/**
 * 以有界周期查询发布 Scheduler 持久化状态指标，不在调度热路径同步写遥测。
 */
@Component
public final class SchedulerDatabaseMetrics {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerDatabaseMetrics.class);
    private static final String ENABLED_SCHEDULES = "flow.schedule.enabled";
    private static final String PENDING_TRIGGERS = "flow.schedule.trigger.pending";
    private static final String PENDING_OUTBOX = "flow.schedule.outbox.pending";
    private static final String DUE_LAG_SECONDS = "flow.schedule.due_lag.seconds";
    private static final String SQL_ENABLED = "SELECT COUNT(*) FROM flow_schedule_definition WHERE enabled = 1";
    private static final String SQL_PENDING_TRIGGERS = "SELECT COUNT(*) FROM flow_schedule_trigger WHERE status = ?";
    private static final String SQL_PENDING_OUTBOX = "SELECT COUNT(*) FROM flow_schedule_outbox WHERE delivery_state = ?";
    private static final String SQL_DUE_LAG = "SELECT COALESCE(GREATEST(TIMESTAMPDIFF(MICROSECOND, MIN(next_fire_at_utc), UTC_TIMESTAMP(3)) / 1000000.0, 0), 0) FROM flow_schedule_definition WHERE enabled = 1 AND next_fire_at_utc <= UTC_TIMESTAMP(3)";
    private static final String PENDING_ENGINE = "PENDING_ENGINE";
    private static final String PENDING = "PENDING";

    private final JdbcTemplate jdbc;
    private final AtomicLong enabledSchedules = new AtomicLong();
    private final AtomicLong pendingTriggers = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong dueLagSeconds = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();

    /** 注册低基数指标并保留服务自有数据库查询入口。 */
    public SchedulerDatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder(ENABLED_SCHEDULES, enabledSchedules, AtomicLong::doubleValue).register(registry);
        Gauge.builder(PENDING_TRIGGERS, pendingTriggers, AtomicLong::doubleValue).register(registry);
        Gauge.builder(PENDING_OUTBOX, pendingOutbox, AtomicLong::doubleValue).register(registry);
        Gauge.builder(DUE_LAG_SECONDS, dueLagSeconds, value -> value.doubleValue() / 1000.0).register(registry);
    }

    /** 周期刷新持久化状态；数据库故障不影响调度业务。 */
    @Scheduled(fixedDelayString = "${flow.observability.refresh-ms:15000}", initialDelayString = "${flow.observability.initial-delay-ms:5000}")
    public void refresh() {
        try {
            enabledSchedules.set(count(SQL_ENABLED));
            pendingTriggers.set(count(SQL_PENDING_TRIGGERS, PENDING_ENGINE));
            pendingOutbox.set(count(SQL_PENDING_OUTBOX, PENDING));
            dueLagSeconds.set(Math.max(0L, Math.round(decimal(SQL_DUE_LAG) * 1000.0)));
            failureReported.set(false);
        } catch (DataAccessException failure) {
            if (failureReported.compareAndSet(false, true)) {
                LOGGER.warn("Scheduler metrics refresh failed: errorType={}", failure.getClass().getSimpleName());
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
