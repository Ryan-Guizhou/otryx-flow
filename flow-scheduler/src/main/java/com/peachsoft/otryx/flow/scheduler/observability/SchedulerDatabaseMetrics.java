package com.peachsoft.otryx.flow.scheduler.observability;

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
 * 周期读取 Scheduler 自有数据库中的调度状态，并发布低基数持久化指标。
 *
 * <p>查询在独立调度线程中执行。查询失败时保留最近一次成功快照，不影响火点领取或 Outbox 事务。</p>
 *
 * @Author Ryan
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/10/27 18:38
 */
@Component
public final class SchedulerDatabaseMetrics {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchedulerDatabaseMetrics.class);
    private static final String DATABASE_REFRESH_STATE = "flow.database.metrics.refresh.state";
    private static final int REFRESH_HEALTHY = 1;
    private static final int REFRESH_FAILED = 2;

    private static final String ENABLED_SCHEDULES = "flow.schedule.enabled";
    private static final String PENDING_TRIGGERS = "flow.schedule.trigger.pending";
    private static final String PENDING_OUTBOX = "flow.schedule.outbox.pending";
    private static final String DUE_LAG_SECONDS = "flow.schedule.due_lag.seconds";

    private static final String SQL_ENABLED =
            "SELECT COUNT(*) FROM flow_schedule_definition WHERE enabled = 1";
    private static final String SQL_PENDING_TRIGGERS =
            "SELECT COUNT(*) FROM flow_schedule_trigger WHERE status = ?";
    private static final String SQL_PENDING_OUTBOX =
            "SELECT COUNT(*) FROM flow_schedule_outbox WHERE delivery_state = ?";
    private static final String SQL_DUE_LAG =
            "SELECT COALESCE(GREATEST(TIMESTAMPDIFF(MICROSECOND, MIN(next_fire_at_utc), "
                    + "UTC_TIMESTAMP(3)) / 1000000.0, 0), 0) "
                    + "FROM flow_schedule_definition WHERE enabled = 1 "
                    + "AND next_fire_at_utc <= UTC_TIMESTAMP(3)";

    private static final String PENDING_ENGINE = "PENDING_ENGINE";
    private static final String PENDING = "PENDING";
    private static final double MILLIS_PER_SECOND = 1000.0;

    private final JdbcTemplate jdbc;
    private final AtomicLong enabledSchedules = new AtomicLong();
    private final AtomicLong pendingTriggers = new AtomicLong();
    private final AtomicLong pendingOutbox = new AtomicLong();
    private final AtomicLong dueLagMillis = new AtomicLong();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final AtomicInteger refreshState = new AtomicInteger();

    /**
     * 注册低基数 Gauge，并绑定 Scheduler 自有数据库的查询入口。
     *
     * @param jdbc Scheduler 自有数据源的 JDBC 操作入口
     * @param registry 暴露指标的注册器
     */
    public SchedulerDatabaseMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder(DATABASE_REFRESH_STATE, refreshState, AtomicInteger::get)
                .description("Database metrics refresh: 0=unknown, 1=healthy, 2=failed")
                .register(registry);
        Gauge.builder(ENABLED_SCHEDULES, enabledSchedules, AtomicLong::doubleValue)
                .description("Number of enabled schedule definitions")
                .register(registry);
        Gauge.builder(PENDING_TRIGGERS, pendingTriggers, AtomicLong::doubleValue)
                .description("Number of schedule triggers awaiting Engine delivery")
                .register(registry);
        Gauge.builder(PENDING_OUTBOX, pendingOutbox, AtomicLong::doubleValue)
                .description("Number of pending Scheduler outbox records")
                .register(registry);
        Gauge.builder(DUE_LAG_SECONDS, dueLagMillis, value -> value.doubleValue() / MILLIS_PER_SECOND)
                .description("Seconds since the earliest overdue enabled schedule became due")
                .register(registry);
    }

    /**
     * 周期刷新数据库状态快照。
     *
     * <p>连续失败只记录一次 WARN；查询恢复后会重新允许记录下一轮故障。</p>
     */
    @Scheduled(
            fixedDelayString = "${flow.observability.refresh-ms:15000}",
            initialDelayString = "${flow.observability.initial-delay-ms:5000}")
    public void refresh() {
        try {
            long nextEnabledSchedules = count(SQL_ENABLED);
            long nextPendingTriggers = count(SQL_PENDING_TRIGGERS, PENDING_ENGINE);
            long nextPendingOutbox = count(SQL_PENDING_OUTBOX, PENDING);
            long nextDueLagMillis =
                    Math.max(0L, Math.round(decimal(SQL_DUE_LAG) * MILLIS_PER_SECOND));

            enabledSchedules.set(nextEnabledSchedules);
            pendingTriggers.set(nextPendingTriggers);
            pendingOutbox.set(nextPendingOutbox);
            dueLagMillis.set(nextDueLagMillis);
            refreshState.set(REFRESH_HEALTHY);
            failureReported.set(false);
        } catch (DataAccessException failure) {
            refreshState.set(REFRESH_FAILED);
            if (failureReported.compareAndSet(false, true)) {
                LOGGER.warn(
                        "Scheduler metrics refresh failed: errorType={}",
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
