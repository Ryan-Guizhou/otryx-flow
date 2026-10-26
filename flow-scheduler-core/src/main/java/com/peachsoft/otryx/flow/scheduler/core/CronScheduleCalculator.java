package com.peachsoft.otryx.flow.scheduler.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.scheduling.support.CronExpression;

/**
 * IANA 时区下的有界 Cron 预览计算器，供 Web 预览与未来 Scheduler 共用。
 *
 * <p>Cron 使用 Spring 六字段（秒、分、时、日、月、周），不支持 Quartz 年字段。
 * 春季不存在的本地时刻不触发，秋季重复本地时刻仅采用较早的偏移时间。
 * 返回的 UTC Instant 严格递增，预览不会创建实际调度记录。
 */
public final class CronScheduleCalculator {
    private static final int MAX_COUNT = 20;
    private static final int MAX_CRON_LENGTH = 120;
    private static final int MAX_SCAN = 512;
    private static final int MAX_ZONE_LENGTH = 64;

    /**
     * 计算从给定 UTC 时刻之后的有限次触发时间。
     *
     * @param expression Spring 六字段 Cron 表达式
     * @param zoneName IANA 时区标识，如 Asia/Shanghai 或 Europe/Berlin
     * @param from 严格起始 UTC 时刻，不包含本身
     * @param count 请求的触发次数，1-20
     * @return 不可变 UTC/本地偏移预览结果
     */
    public Preview preview(String expression, String zoneName, Instant from, int count) {
        if (from == null || count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("Invalid preview request");
        }
        if (expression == null || expression.isBlank() || expression.length() > MAX_CRON_LENGTH
                || expression.trim().split("\\s+").length != 6) {
            throw new IllegalArgumentException("Cron requires exactly six fields");
        }
        if (zoneName == null || zoneName.isBlank() || zoneName.length() > MAX_ZONE_LENGTH
                || !ZoneId.getAvailableZoneIds().contains(zoneName)) {
            throw new IllegalArgumentException("Unknown IANA time zone");
        }
        final CronExpression cron;
        try {
            cron = CronExpression.parse(expression.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Cron expression");
        }
        ZoneId zone = ZoneId.of(zoneName);
        ZonedDateTime cursor = from.atZone(zone);
        List<Occurrence> occurrences = new ArrayList<>(count);
        int overlapsSkipped = 0;
        for (int scan = 0; scan < MAX_SCAN && occurrences.size() < count; scan++) {
            ZonedDateTime next = cron.next(cursor);
            if (next == null) break;
            if (!next.toInstant().isAfter(cursor.toInstant())) {
                throw new IllegalStateException("Cron calculation did not advance");
            }
            cursor = next;
            List<ZoneOffset> validOffsets = zone.getRules().getValidOffsets(next.toLocalDateTime());
            if (validOffsets.size() == 2 && !next.getOffset().equals(validOffsets.getFirst())) {
                overlapsSkipped++;
                continue;
            }
            occurrences.add(new Occurrence(next.toInstant().toString(),
                    next.toLocalDateTime().toString(), next.getOffset().toString()));
        }
        return new Preview(expression.trim(), zone.getId(), from.toString(),
                List.copyOf(occurrences), overlapsSkipped);
    }

    /** 预览结果，绝不表示任务已经被调度执行。 */
    public record Preview(String expression, String timeZone, String fromUtc,
                          List<Occurrence> occurrences, int overlapOccurrencesSkipped) { }

    /** UTC 时刻及其对应本地墙上时间、偏移量。 */
    public record Occurrence(String utc, String local, String offset) { }
}
