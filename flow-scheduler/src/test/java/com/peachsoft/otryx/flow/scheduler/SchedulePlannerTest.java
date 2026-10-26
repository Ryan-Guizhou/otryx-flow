package com.peachsoft.otryx.flow.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 覆盖恢复窗口、有限追赶、游标单调前移和 DST 一致性。 */
class SchedulePlannerTest {
    private static final String EVERY_SECOND = "* * * * * *";
    private static final String UTC = "Etc/UTC";
    private final SchedulePlanner planner = new SchedulePlanner();

    @Test
    void skipOverdueDoesNotCreateFalseRun() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = due.plusSeconds(120);
        var result = planner.plan(EVERY_SECOND, UTC, "SKIP", due, now);
        assertTrue(result.fires().isEmpty());
        assertTrue(result.nextFireUtc().isAfter(now));
    }

    @Test
    void fireOnceProducesOneLogicalEventAfterLongDowntime() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        var result = planner.plan(EVERY_SECOND, UTC, "FIRE_ONCE", due, due.plusSeconds(120));
        assertEquals(1, result.fires().size());
        assertEquals(due, result.fires().getFirst());
        assertTrue(result.nextFireUtc().isAfter(due.plusSeconds(120)));
    }

    @Test
    void boundedCatchUpHonorsLookbackAndDoesNotSkipUndeliveredCursor() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = due.plusSeconds(7200);
        var result = planner.plan(EVERY_SECOND, UTC, "CATCH_UP", due, now);
        assertEquals(8, result.fires().size());
        assertTrue(result.fires().getFirst().isAfter(now.minusSeconds(3600)));
        assertTrue(result.nextFireUtc().isAfter(result.fires().getLast()));
        assertTrue(result.nextFireUtc().isBefore(now));
    }

    @Test
    void normalTickMakesMonotoneLimitedBatch() {
        Instant due = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = due.plusSeconds(12);
        var result = planner.plan(EVERY_SECOND, UTC, "FIRE_ONCE", due, now);
        assertEquals(8, result.fires().size());
        assertEquals(due, result.fires().getFirst());
        assertFalse(result.nextFireUtc().isBefore(result.fires().getLast()));
    }

    @Test
    void springForwardGapUsesSameCoreAsPreview() {
        Instant cursor = planner.next("0 30 2 * * *", "Europe/Berlin",
                Instant.parse("2026-03-28T01:30:00Z"));
        assertEquals(Instant.parse("2026-03-30T00:30:00Z"), cursor);
    }
}
