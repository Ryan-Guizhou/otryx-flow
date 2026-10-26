package com.peachsoft.otryx.flow.api.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.peachsoft.otryx.flow.scheduler.core.CronScheduleCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Cron 预览 REST 状态与计算结果边界。 */
class CronPreviewControllerTest {
    private final CronPreviewController controller = new CronPreviewController();

    @Test
    void successIncludesUtcAndIanaZone() {
        var result = controller.preview(new CronPreviewController.Request("0 */15 * * * *", "Etc/UTC",
                "2026-10-26T10:00:00Z", 2));
        assertEquals(HttpStatus.OK, result.getStatusCode());
        var preview = assertInstanceOf(CronScheduleCalculator.Preview.class, result.getBody());
        assertEquals("2026-10-26T10:15:00Z", preview.occurrences().getFirst().utc());
    }

    @Test
    void unsupportedTimeZoneAndExpressionAreBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.preview(new CronPreviewController.Request(
                "0 0 9 * * *", "Unknown/City", "2026-10-26T10:00:00Z", 2)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.preview(new CronPreviewController.Request(
                "* * *", "Etc/UTC", "2026-10-26T10:00:00Z", 2)).getStatusCode());
    }
}
