package com.peachsoft.otryx.flow.scheduler;

import com.peachsoft.otryx.flow.scheduler.core.CronScheduleCalculator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * UTC 游标演进和 Misfire 规则的纯函数实现，所有 Scheduler 副本使用同一时区/DST 计算核心。
 * <p>每次事务最多生成八个逻辑火点，避免高频 Cron 长时间占有数据库行锁。
 */
public final class SchedulePlanner {
    private static final int MAX_FIRES_PER_SCAN = 8;
    private static final long GRACE_SECONDS = 30L;
    private static final long CATCHUP_WINDOW_SECONDS = 3600L;
    private final CronScheduleCalculator cron = new CronScheduleCalculator();

    /** 三种首发 Misfire 策略。 */
    public enum MisfirePolicy { SKIP, FIRE_ONCE, CATCH_UP }

    /** 单行扫描结果，nextFireUtc 必须严格晚于原有游标。 */
    public record Plan(List<Instant> fires, Instant nextFireUtc) { }

    /** 计算某一已领取计划的有限火点和下一游标，不执行远程 RPC。 */
    public Plan plan(String expression, String timeZone, String policyName,
            Instant persistedNext, Instant databaseNow) {
        Objects.requireNonNull(persistedNext, "persistedNext");
        Objects.requireNonNull(databaseNow, "databaseNow");
        MisfirePolicy policy = MisfirePolicy.valueOf(policyName);
        if (persistedNext.isAfter(databaseNow)) {
            return new Plan(List.of(), persistedNext);
        }
        boolean late = persistedNext.isBefore(databaseNow.minusSeconds(GRACE_SECONDS));
        if (late && policy == MisfirePolicy.SKIP) {
            return new Plan(List.of(), next(expression, timeZone, databaseNow));
        }
        if (late && policy == MisfirePolicy.FIRE_ONCE) {
            return new Plan(List.of(persistedNext), next(expression, timeZone, databaseNow));
        }
        Instant cursor = persistedNext;
        if (policy == MisfirePolicy.CATCH_UP && persistedNext.isBefore(
                databaseNow.minusSeconds(CATCHUP_WINDOW_SECONDS))) {
            cursor = next(expression, timeZone, databaseNow.minusSeconds(CATCHUP_WINDOW_SECONDS));
        }
        List<Instant> due = new ArrayList<>(MAX_FIRES_PER_SCAN);
        while (!cursor.isAfter(databaseNow) && due.size() < MAX_FIRES_PER_SCAN) {
            due.add(cursor);
            cursor = next(expression, timeZone, cursor);
        }
        if (!cursor.isAfter(persistedNext)) {
            throw new IllegalStateException("Schedule cursor did not advance");
        }
        return new Plan(List.copyOf(due), cursor);
    }

    /** 在 fromUtc 后计算首个 Cron 火点；与前端预览使用完全相同的计算规则。 */
    public Instant next(String expression, String timeZone, Instant fromUtc) {
        var preview = cron.preview(expression, timeZone, fromUtc, 1);
        if (preview.occurrences().isEmpty()) {
            throw new IllegalArgumentException("Cron expression has no upcoming occurrence");
        }
        return Instant.parse(preview.occurrences().getFirst().utc());
    }
}
