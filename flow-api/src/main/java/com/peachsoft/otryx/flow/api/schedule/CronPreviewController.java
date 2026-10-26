package com.peachsoft.otryx.flow.api.schedule;

import com.peachsoft.otryx.flow.scheduler.core.CronScheduleCalculator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cron/时区预览入口。采用本地纯计算核心，不访问 Registry 或将预览冒充真实调度。
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class CronPreviewController {
    private static final CronScheduleCalculator CALCULATOR = new CronScheduleCalculator();
    private static final String BAD_CRON = "INVALID_CRON_SCHEDULE";

    /** 计算严格晚于 fromUtc 的有限次 UTC 触发预览。 */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> preview(@Valid @RequestBody Request body) {
        try {
            Instant from = Instant.parse(body.fromUtc());
            return ResponseEntity.ok(CALCULATOR.preview(body.expression(), body.timeZone(), from, body.limit()));
        } catch (IllegalArgumentException | DateTimeParseException invalid) {
            return ResponseEntity.badRequest().body(Map.of("code", BAD_CRON));
        }
    }

    /** 用户提供的确定性 Cron 预览输入。 */
    public record Request(
            @NotBlank @Size(max = 120) String expression,
            @NotBlank @Size(max = 64) String timeZone,
            @NotBlank String fromUtc,
            @Min(1) @Max(20) int limit) { }
}
