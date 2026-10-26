package com.peachsoft.otryx.flow.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 多实例行锁扫描器：READ_COMMITTED / SKIP_LOCKED / 唯一 Fire / 同事务 Outbox。
 * <p>整个数据库事务内禁止 RPC；后续 Engine 交付独立使用已提交的 Outbox。
 */
@Component
public class ScheduleScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduleScanner.class);
    private static final int LOCK_BATCH = 12;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SchedulePlanner planner;
    private final ObjectMapper mapper;

    /** 注入 Scheduler 私有数据库、事务管理器和统一 Cron 核心。 */
    public ScheduleScanner(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(10);
        this.planner = new SchedulePlanner();
        this.mapper = mapper;
    }

    /** 固定延迟扫描；数据库出错停止本轮，不产生任何虚假成功事件。 */
    @Scheduled(fixedDelayString = "${flow.scheduler.poll-ms:1000}")
    public void poll() {
        try {
            int fires = scanOnce();
            if (fires > 0) LOGGER.info("Committed {} logical schedule fires", fires);
        } catch (RuntimeException unavailable) {
            LOGGER.warn("Scheduler scan failed: {}", unavailable.getClass().getSimpleName());
        }
    }

    /**
     * 在单个短事务内领取有限数量到期计划，计算火点、写 Outbox 并 CAS 前移游标。
     * @return 本轮实际持久化的逻辑火点数量
     */
    public int scanOnce() {
        Integer committed = tx.execute(status -> {
            Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
            if (now == null) throw new IllegalStateException("Database clock unavailable");
            Instant databaseNow = now.toInstant();
            var due = jdbc.query("SELECT id, job_id, job_revision, schedule_revision, cron_expr, time_zone, "
                    + "misfire_policy, next_fire_at_utc, version FROM flow_schedule_definition "
                    + "WHERE enabled = 1 AND next_fire_at_utc <= ? "
                    + "ORDER BY next_fire_at_utc, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, row) -> readRow(rs), now, LOCK_BATCH);
            int total = 0;
            for (ScheduleRow schedule : due) {
                SchedulePlanner.Plan result = planner.plan(schedule.expression(), schedule.timeZone(),
                        schedule.misfirePolicy(), schedule.nextFireUtc(), databaseNow);
                for (Instant fire : result.fires()) {
                    String eventId = fireId(schedule.id(), schedule.scheduleRevision(), fire);
                    Timestamp createdAt = Timestamp.from(databaseNow);
                    jdbc.update("INSERT INTO flow_schedule_trigger (id, schedule_id, schedule_revision, "
                            + "planned_fire_at_utc, origin, status, created_at) "
                            + "VALUES (?, ?, ?, ?, 'CRON', 'PENDING_ENGINE', ?)", eventId,
                            schedule.id(), schedule.scheduleRevision(), Timestamp.from(fire), createdAt);
                    jdbc.update("INSERT INTO flow_schedule_outbox (event_id, trigger_id, event_type, "
                            + "payload_json, delivery_state, attempts, created_at) "
                            + "VALUES (?, ?, 'SCHEDULE_FIRE', ?, 'PENDING', 0, ?)",
                            eventId, eventId, encodePayload(schedule, fire, eventId), createdAt);
                    total++;
                }
                int updated = jdbc.update("UPDATE flow_schedule_definition SET next_fire_at_utc = ?, "
                                + "version = version + 1, updated_at = ? "
                                + "WHERE id = ? AND version = ? AND next_fire_at_utc = ? AND enabled = 1",
                        Timestamp.from(result.nextFireUtc()), Timestamp.from(databaseNow), schedule.id(),
                        schedule.version(), Timestamp.from(schedule.nextFireUtc()));
                if (updated != 1) {
                    throw new IllegalStateException("Schedule cursor CAS rejected");
                }
            }
            return total;
        });
        return committed == null ? 0 : committed;
    }

    private String encodePayload(ScheduleRow schedule, Instant fire, String id) {
        try {
            return mapper.writeValueAsString(Map.of("eventId", id, "scheduleId", schedule.id(),
                    "jobId", schedule.jobId(), "jobRevision", schedule.jobRevision(),
                    "scheduleRevision", schedule.scheduleRevision(), "plannedFireUtc", fire.toString()));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Cannot serialize scheduler outbox", impossible);
        }
    }

    /** 根据业务唯一键派生稳定事件 ID；重试或数据库恢复仍使用原事件身份。 */
    private static String fireId(String id, int revision, Instant fire) {
        String stable = id + ":" + revision + ":" + fire + ":CRON";
        return UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static ScheduleRow readRow(ResultSet rs) throws SQLException {
        return new ScheduleRow(rs.getString("id"), rs.getString("job_id"), rs.getInt("job_revision"),
                rs.getInt("schedule_revision"), rs.getString("cron_expr"), rs.getString("time_zone"),
                rs.getString("misfire_policy"), rs.getTimestamp("next_fire_at_utc").toInstant(),
                rs.getLong("version"));
    }

    private record ScheduleRow(String id, String jobId, int jobRevision, int scheduleRevision,
            String expression, String timeZone, String misfirePolicy, Instant nextFireUtc,
            long version) { }
}
