package com.peachsoft.otryx.flow.api.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.api.audit.FlowAuditRecorder;
import com.peachsoft.otryx.flow.api.job.HandlerPolicy;
import com.peachsoft.otryx.flow.contracts.v1.CancelJobRunCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogPageView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchTaskView;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.flow.contracts.v1.HandlerCatalogRpc;
import com.peachsoft.otryx.flow.contracts.v1.HandlerDefinitionView;
import com.peachsoft.otryx.flow.contracts.v1.JobRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobView;
import com.peachsoft.otryx.flow.contracts.v1.JobRunMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.JobRunRpc;
import com.peachsoft.otryx.flow.contracts.v1.JobRunView;
import com.peachsoft.otryx.flow.contracts.v1.ProjectRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleUpdateCommand;
import com.peachsoft.otryx.flow.contracts.v1.StartManualJobRunCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkerRegistrationView;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 普通 Job 的立即执行、停止、重试、Cron 修改和 Handler 目录。
 *
 * <p>身份只取自登录会话。容量等待和取消接受都不表示业务已经成功。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
@RestController
public class JobExecutionController {
    private static final String RPC = "1.0.0";
    private static final String KEY = "[a-zA-Z0-9_-]{16,80}";
    private static final int MAX_OVERRIDE_BYTES = 16 * 1024;
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    @OtryxRpcReference(version = RPC) private JobRunRpc runs;
    @OtryxRpcReference(version = RPC) private JobRpc jobs;
    @OtryxRpcReference(version = RPC) private DispatcherRpc dispatcher;
    @OtryxRpcReference(version = RPC) private HandlerCatalogRpc handlers;
    @OtryxRpcReference(version = RPC) private ScheduleRpc schedules;
    @OtryxRpcReference(version = RPC) private ProjectRpc projects;
    @Autowired(required = false) private FlowAuditRecorder audit;

    /** 对已发布版本创建一次手动 Run。没有在线 Handler 时拒绝，而不是假装成功。 */
    @PostMapping("/api/v1/projects/{projectId}/jobs/{jobId}/runs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> start(Principal user, @PathVariable String projectId, @PathVariable String jobId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody StartBody body) {
        if (!id(projectId) || !id(jobId) || key == null || !key.matches(KEY)) return bad("INVALID_RUN_REQUEST");
        if (!allowed(user, projectId)) return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "PROJECT_NOT_FOUND"));
        JobView definition = jobs.get(user.getName(), projectId, jobId);
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_NOT_FOUND"));
        }
        if (!definition.enabled() || definition.archived()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "JOB_DISABLED"));
        }
        JobRevisionView revision = jobs.revision(user.getName(), projectId, jobId, body.jobRevision());
        if (revision == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_REVISION_NOT_FOUND"));
        }
        if (body.parameterOverrides() != null && !body.parameterOverrides().isBlank()) {
            if (!validParameterOverride(body.parameterOverrides())) return bad("INVALID_PARAMETER_OVERRIDE");
            if (!HandlerPolicy.allowed(revision.handlerKey(), body.parameterOverrides())) {
                return bad("HANDLER_REJECTED");
            }
        }
        if (!handlerReady(revision.handlerKey())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "HANDLER_UNAVAILABLE"));
        }
        JobRunMutationReply result = runs.startManual(new StartManualJobRunCommand(user.getName(), projectId, jobId,
                body.jobRevision(), key, body.parameterOverrides(), null));
        record(user, result.code());
        return runResponse(result, result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED);
    }

    /** 最近的普通 Job Run。筛选在返回前完成，容量等待不会被当成失败。 */
    @GetMapping("/api/v1/projects/{projectId}/job-runs")
    public ResponseEntity<?> list(Principal user, @PathVariable String projectId,
            @RequestParam(required = false) String jobId, @RequestParam(required = false) String origin,
            @RequestParam(required = false) String status, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String worker) {
        if (!id(projectId) || !allowed(user, projectId)) return bad("INVALID_RUN_REQUEST");
        Instant start = parseTime(from);
        Instant end = parseTime(to);
        if ((from != null && !from.isBlank() && start == null) || (to != null && !to.isBlank() && end == null)) {
            return bad("INVALID_RUN_REQUEST");
        }
        Map<String, String> workers = new LinkedHashMap<>();
        for (DispatchTaskView task : dispatcher.list(user.getName(), projectId).tasks()) {
            workers.put(task.runId(), task.workerId());
        }
        var matched = Arrays.stream(runs.list(user.getName(), projectId).runs()).filter(run ->
                (jobId == null || jobId.isBlank() || jobId.equals(run.jobId()))
                && (origin == null || origin.isBlank() || origin.equals(run.origin()))
                && (status == null || status.isBlank() || status.equals(run.status()))
                && within(run.createdAt(), start, end)
                && (worker == null || worker.isBlank() || worker.equals(workers.get(run.id())))).toList();
        return ResponseEntity.ok(matched);
    }

    /** Run、队列任务和日志入口需要的持久状态。 */
    @GetMapping("/api/v1/projects/{projectId}/job-runs/{runId}")
    public ResponseEntity<?> detail(Principal user, @PathVariable String projectId, @PathVariable String runId) {
        if (!id(projectId) || !id(runId)) return bad("INVALID_RUN_REQUEST");
        JobRunView run = runs.detail(user.getName(), projectId, runId);
        if (run == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "RUN_NOT_FOUND"));
        DispatchTaskView task = Arrays.stream(dispatcher.list(user.getName(), projectId).tasks())
                .filter(item -> runId.equals(item.runId())).findFirst().orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("run", run);
        body.put("task", task);
        body.put("attempts", dispatcher.listAttempts(user.getName(), projectId, runId));
        return ResponseEntity.ok(body);
    }

    /** 停止这一次执行。暂停 Cron 不是这个接口。 */
    @PostMapping("/api/v1/projects/{projectId}/job-runs/{runId}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> cancel(Principal user, @PathVariable String projectId, @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody(required = false) CancelBody body) {
        if (!id(projectId) || !id(runId) || key == null || !key.matches(KEY)) return bad("INVALID_RUN_REQUEST");
        long version = body == null ? 0 : body.expectedRunVersion();
        JobRunMutationReply result = runs.cancel(new CancelJobRunCommand(user.getName(), projectId, runId, key,
                version));
        record(user, result.code());
        return runResponse(result, HttpStatus.ACCEPTED);
    }

    /** 用新的 Run 重新执行。原失败记录保持不变。 */
    @PostMapping("/api/v1/projects/{projectId}/job-runs/{runId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> retry(Principal user, @PathVariable String projectId, @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String key) {
        if (!id(projectId) || !id(runId) || key == null || !key.matches(KEY)) return bad("INVALID_RUN_REQUEST");
        JobRunView previous = runs.detail(user.getName(), projectId, runId);
        if (previous == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "RUN_NOT_FOUND"));
        if (!"FAILED".equals(previous.status()) && !"CANCELLED".equals(previous.status())
                && !"SUCCEEDED".equals(previous.status())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "RUN_NOT_TERMINAL"));
        }
        JobView definition = jobs.get(user.getName(), projectId, previous.jobId());
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_NOT_FOUND"));
        }
        if (!definition.enabled() || definition.archived()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "JOB_DISABLED"));
        }
        JobRevisionView revision = jobs.revision(user.getName(), projectId, previous.jobId(), previous.jobRevision());
        if (revision == null || !handlerReady(revision.handlerKey())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "HANDLER_UNAVAILABLE"));
        }
        JobRunMutationReply result = runs.startManual(new StartManualJobRunCommand(user.getName(), projectId,
                previous.jobId(), previous.jobRevision(), key, null, previous.id()));
        record(user, result.code());
        return runResponse(result, result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED);
    }

    /** 有界、已脱敏的 Worker 日志。 */
    @GetMapping("/api/v1/projects/{projectId}/job-runs/{runId}/logs")
    public ResponseEntity<?> logs(Principal user, @PathVariable String projectId, @PathVariable String runId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        if (!id(projectId) || !id(runId)) return bad("INVALID_RUN_REQUEST");
        if (runs.detail(user.getName(), projectId, runId) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "RUN_NOT_FOUND"));
        }
        DispatchLogPageView page = dispatcher.queryLogs(user.getName(), projectId, new String[]{runId}, cursor,
                Math.min(Math.max(limit, 1), 100));
        return ResponseEntity.ok(page);
    }

    /** 修改 Cron。不改已经产生的火点。 */
    @PutMapping("/api/v1/projects/{projectId}/schedules/{scheduleId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> updateSchedule(Principal user, @PathVariable String projectId,
            @PathVariable String scheduleId, @Valid @RequestBody ScheduleEdit body) {
        if (!id(projectId) || !id(scheduleId) || !id(body.jobId())) return bad("INVALID_SCHEDULE_REQUEST");
        var result = schedules.update(new ScheduleUpdateCommand(user.getName(), projectId, scheduleId,
                body.expectedVersion(), body.jobId(), body.jobRevision(), body.expression(), body.timeZone(),
                body.misfirePolicy()));
        return switch (result.code()) {
            case "OK" -> ResponseEntity.ok(result.schedule());
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "SCHEDULE_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SCHEDULE_CONFLICT"));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "SCHEDULE_RPC_ERROR"));
        };
    }

    /** 归档计划并停止未来触发。 */
    @PostMapping("/api/v1/projects/{projectId}/schedules/{scheduleId}/archive")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> archiveSchedule(Principal user, @PathVariable String projectId,
            @PathVariable String scheduleId, @Valid @RequestBody VersionBody body) {
        if (!id(projectId) || !id(scheduleId)) return bad("INVALID_SCHEDULE_REQUEST");
        var result = schedules.archive(user.getName(), projectId, scheduleId, body.expectedVersion());
        return switch (result.code()) {
            case "OK" -> ResponseEntity.ok(result.schedule());
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "SCHEDULE_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "SCHEDULE_CONFLICT"));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "SCHEDULE_RPC_ERROR"));
        };
    }

    /** 定时任务立即执行一次。不制造 Cron 火点。 */
    @PostMapping("/api/v1/projects/{projectId}/schedules/{scheduleId}/run-now")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    public ResponseEntity<?> runNow(Principal user, @PathVariable String projectId, @PathVariable String scheduleId,
            @RequestHeader("Idempotency-Key") String key) {
        if (!id(projectId) || !id(scheduleId) || key == null || !key.matches(KEY)) return bad("INVALID_RUN_REQUEST");
        var schedule = Arrays.stream(schedules.list(user.getName(), projectId).schedules())
                .filter(item -> scheduleId.equals(item.id())).findFirst().orElse(null);
        if (schedule == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "SCHEDULE_NOT_FOUND"));
        }
        JobView definition = jobs.get(user.getName(), projectId, schedule.jobId());
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "JOB_NOT_FOUND"));
        }
        if (!definition.enabled() || definition.archived()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "JOB_DISABLED"));
        }
        JobRevisionView revision = jobs.revision(user.getName(), projectId, schedule.jobId(), schedule.jobRevision());
        if (revision == null || !handlerReady(revision.handlerKey())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "HANDLER_UNAVAILABLE"));
        }
        JobRunMutationReply result = runs.startManual(new StartManualJobRunCommand(user.getName(), projectId,
                schedule.jobId(), schedule.jobRevision(), key, null, null));
        return runResponse(result, result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED);
    }

    /** 已登记的 Handler。在线数量来自 Worker 心跳。 */
    @GetMapping("/api/v1/handlers")
    public HandlerDefinitionView[] handlers() {
        return handlers.list();
    }

    /** 心跳目录。过期记录显示为离线。 */
    @GetMapping("/api/v1/workers")
    public WorkerRegistrationView[] workers() {
        return dispatcher.listWorkers();
    }

    private boolean allowed(Principal user, String projectId) {
        return projects == null || projects.hasAccess(user.getName(), projectId);
    }

    private static Instant parseTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException invalid) { return null; }
    }

    private static boolean within(String createdAt, Instant start, Instant end) {
        Instant created = Instant.parse(createdAt);
        if (start != null && created.isBefore(start)) return false;
        return end == null || !created.isAfter(end);
    }

    private static boolean validParameterOverride(String json) {
        if (json == null || json.isBlank()
                || json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OVERRIDE_BYTES) {
            return false;
        }
        try {
            JsonNode value = JSON_MAPPER.readTree(json);
            return value != null && value.isObject();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return false;
        }
    }

    private boolean handlerReady(String handlerKey) {
        boolean known = Arrays.stream(handlers.list()).anyMatch(item -> item.enabled()
                && handlerKey.equals(item.handlerKey()));
        if (!known) return false;
        return Arrays.stream(dispatcher.listWorkers()).anyMatch(worker -> "ONLINE".equals(worker.status())
                && Arrays.asList(worker.handlersCsv().split(",")).contains(handlerKey));
    }

    private void record(Principal user, String code) {
        if (audit != null && user != null) audit.record(user.getName(), "JOB_RUN", null, code);
    }

    private static ResponseEntity<?> runResponse(JobRunMutationReply result, HttpStatus accepted) {
        return switch (result.code()) {
            case "CREATED", "DUPLICATE", "CANCEL_REQUESTED" -> ResponseEntity.status(
                    "DUPLICATE".equals(result.code()) ? HttpStatus.OK : accepted).body(result);
            case "TERMINAL" -> ResponseEntity.ok(result);
            case "NOT_FOUND" -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "RUN_NOT_FOUND"));
            case "CONFLICT" -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "RUN_CONFLICT"));
            default -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "RUN_RPC_ERROR"));
        };
    }

    private static ResponseEntity<?> bad(String code) {
        return ResponseEntity.badRequest().body(Map.of("code", code));
    }

    private static boolean id(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** 立即执行只接受已经发布的版本号。 */
    public record StartBody(@Min(1) int jobRevision, String parameterOverrides) { }
    /** 可选的 Run 版本。0 表示不校验。 */
    public record CancelBody(long expectedRunVersion) { }
    /** Cron 编辑正文。 */
    public record ScheduleEdit(@Min(1) long expectedVersion, @NotBlank String jobId, @Min(1) int jobRevision,
            @NotBlank @Size(max = 120) String expression, @NotBlank @Size(max = 64) String timeZone,
            @NotBlank @Pattern(regexp = "SKIP|FIRE_ONCE|CATCH_UP") String misfirePolicy) { }
    /** 归档时期望的计划版本。 */
    public record VersionBody(@Min(1) long expectedVersion) { }
}
