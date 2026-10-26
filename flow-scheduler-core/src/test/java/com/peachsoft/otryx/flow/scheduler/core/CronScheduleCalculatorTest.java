package com.peachsoft.otryx.flow.scheduler.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** 验证 Cron 在 UTC 与夏令时边界的稳定语义。 */
class CronScheduleCalculatorTest {
    private final CronScheduleCalculator calculator = new CronScheduleCalculator();

    @Test
    void utcScheduleIsStrictlyAfterStart() {
        var preview = calculator.preview("0 */15 * * * *", "Etc/UTC",
                Instant.parse("2026-10-26T10:00:00Z"), 3);
        assertEquals(3, preview.occurrences().size());
        assertEquals("2026-10-26T10:15:00Z", preview.occurrences().getFirst().utc());
        assertEquals("2026-10-26T10:30:00Z", preview.occurrences().get(1).utc());
        assertEquals("2026-10-26T10:45:00Z", preview.occurrences().get(2).utc());
    }

    @Test
    void springGapSkipsNonexistentBerlinHalfPastTwo() {
        var preview = calculator.preview("0 30 2 * * *", "Europe/Berlin",
                Instant.parse("2026-03-28T00:00:00Z"), 3);
        assertEquals("2026-03-28T01:30:00Z", preview.occurrences().getFirst().utc());
        // On 2026-03-29 02:30 local time does not exist in Berlin.
        assertEquals("2026-03-30T00:30:00Z", preview.occurrences().get(1).utc());
    }

    @Test
    void autumnOverlapCannotEmitSameLocalClockTwice() {
        var preview = calculator.preview("0 30 2 * * *", "Europe/Berlin",
                Instant.parse("2026-10-24T00:00:00Z"), 4);
        var unique = new HashSet<String>();
        for (var fire : preview.occurrences()) {
            assertTrue(unique.add(fire.local()));
        }
        assertEquals("2026-10-25T00:30:00Z", preview.occurrences().get(1).utc());
        assertEquals("2026-10-26T01:30:00Z", preview.occurrences().get(2).utc());
    }

    @Test
    void rejectMalformedAndUnboundedRequests() {
        assertThrows(IllegalArgumentException.class, () -> calculator.preview("* * * * *", "Etc/UTC",
                Instant.now(), 5));
        assertThrows(IllegalArgumentException.class, () -> calculator.preview("0 0 * * * *", "Not/AZone",
                Instant.now(), 5));
        assertThrows(IllegalArgumentException.class, () -> calculator.preview("0 0 * * * *", "Etc/UTC",
                Instant.now(), 21));
    }
}
