package com.peachsoft.otryx.flow.api.schedule;

import com.peachsoft.otryx.flow.contracts.v1.ScheduleCreateCommand;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleToggleCommand;
import com.peachsoft.otryx.flow.scheduler.core.CronScheduleCalculator;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST 鉴权门面：通过 OTRYX RPC 调用独立 Scheduler；业务调用不共享底层数据库。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/schedules")
public class ScheduleController {
    private static final String RPC_VERSION = "1.0.0";
    private static final String KEY_PATTERN = "[a-zA-Z0-9_-]{16,80}";
    private static final CronScheduleCalculator CALCULATOR = new CronScheduleCalculator();

    @OtryxRpcReference(version = RPC_VERSION)
    private ScheduleRpc scheduler;

    /** 查询当前用户项目下的真实已持久化调度定义。 */
    @GetMapping
    public ResponseEntity<?> list(Principal user, @PathVariable String projectId) {
        if (!validId(projectId)) return bad();
        return ResponseEntity.ok(Arrays.asList(scheduler.list(user.getName(), projectId).schedules()));
    }

    /** 校验已发布 JobRevision 后，幂等创建 Cron 计划。 */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> create(Principal user, @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CreateBody body) {
        if (!validId(projectId) || !validId(body.jobId()) || key == null || !key.matches(KEY_PATTERN)) {
            return bad();
        }
        try {
            CALCULATOR.preview(body.expression(), body.timeZone(), Instant.now(), 1);
        } catch (IllegalArgumentException invalid) {
            return bad();
        }
        var response = scheduler.create(new ScheduleCreateCommand(user.getName(), projectId,
                body.jobId(), body.jobRevision(), body.expression(), body.timeZone(),
                body.misfirePolicy(), key));
        return respond(response, response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED);
    }

    /** 查询真实已提交火点，未连接 Engine 前均保持 PENDING_ENGINE。 */
    @GetMapping("/{scheduleId}/triggers")
    public ResponseEntity<?> triggers(Principal user, @PathVariable String projectId,
            @PathVariable String scheduleId) {
        if (!validId(projectId) || !validId(scheduleId)) return bad();
        return ResponseEntity.ok(Arrays.asList(scheduler.triggers(user.getName(), projectId, scheduleId).triggers()));
    }

    /** CAS 启停；重新启用的下一计划从当前 UTC 计算而非盲目追赶。 */
    @PostMapping("/{scheduleId}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> toggle(Principal user, @PathVariable String projectId,
            @PathVariable String scheduleId, @Valid @RequestBody ToggleBody body) {
        if (!validId(projectId) || !validId(scheduleId)) return bad();
        return respond(scheduler.toggle(new ScheduleToggleCommand(user.getName(), projectId,
                scheduleId, body.expectedVersion(), body.enabled())), HttpStatus.OK);
    }

    private static ResponseEntity<?> respond(ScheduleMutationReply result, HttpStatus success) {
        return switch (result.code()) {
            case "OK" -> ResponseEntity.status(success).body(result.schedule());
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "SCHEDULE_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SCHEDULE_CONFLICT"));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "SCHEDULE_RPC_ERROR"));
        };
    }

    private static ResponseEntity<?> bad() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_SCHEDULE_REQUEST"));
    }

    private static boolean validId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** 任务绑定必须明确到已发布 revision，不接受草稿版本。 */
    public record CreateBody(
            @NotBlank String jobId,
            @Min(1) int jobRevision,
            @NotBlank @Size(max = 120) String expression,
            @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Pattern(regexp = "SKIP|FIRE_ONCE|CATCH_UP") String misfirePolicy) { }

    /** 期望版本为正值；Spring MVC 显式 JSON 布尔值。 */
    public record ToggleBody(@Min(1) long expectedVersion, boolean enabled) { }
}
