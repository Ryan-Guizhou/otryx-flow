package com.peachsoft.otryx.flow.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCompleteCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchCancelReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchHeartbeatCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLeaseView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogBatchCommand;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogBatchReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogEntry;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogPageView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchLogView;
import com.peachsoft.otryx.flow.contracts.v1.DispatchTaskListReply;
import com.peachsoft.otryx.flow.contracts.v1.DispatchTaskView;
import com.peachsoft.otryx.flow.contracts.v1.DispatcherRpc;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dispatcher 队列与租约协议：独立数据库、持久化尝试、leaseEpoch/leaseToken 双重 fencing。
 * <p>Worker SDK 在下一阶段提供自动执行，当前仅提供受限领取与完成 RPC 契约。</p>
 */
@OtryxRpcService(interfaceClass = DispatcherRpc.class, version = "1.0.0")
public class DispatcherRpcService implements DispatcherRpc {
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern WORKER = Pattern.compile("[a-zA-Z0-9_.-]{3,64}");
    private static final Pattern HANDLER = Pattern.compile("[a-z][a-z0-9_.-]{2,127}");
    private static final int MAX_PARAMETERS_BYTES = 16 * 1024;
    private static final int CLAIM_LIMIT = 8;
    private static final int LIST_LIMIT = 100;
    private static final int REAPER_LIMIT = 16;
    private static final int MAX_LOG_BATCH = 32;
    private static final int MAX_LOG_QUERY_NODES = 300;
    private static final int MAX_LOGS_PER_ATTEMPT = 1000;
    private static final int MAX_LOG_MESSAGE_BYTES = 2048;
    private static final int MAX_LOG_FIELDS = 16;
    private static final int MAX_LOG_FIELD_VALUE_BYTES = 80;
    private static final int MAX_LOG_FIELDS_BYTES = 4096;
    private static final Pattern SENSITIVE_FIELD = Pattern.compile("(?i).*(password|token|secret|authorization|cookie|credential|api[_-]?key).*");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile("(?i)(password|token|secret|authorization|cookie|credential|api[_-]?key)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final long LEASE_SECONDS = 30;
    private static final String READY = "READY";
    private static final String LEASED = "LEASED";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String STALE = "STALE";
    private static final String CANCELLED = "CANCELLED";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    /** 数据库事务必须仅涉及 Dispatcher 自有 DataSource。 */
    public DispatcherRpcService(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(8);
        this.mapper = mapper;
    }

    /** 稳定 engineRunId 与 payloadFingerprint 防止重复和内容篡改。 */
    @Override
    public DispatchEnqueueReply enqueue(DispatchEnqueueCommand command) {
        validateEnqueue(command);
        String fingerprint = fingerprint(command);
        DispatchEnqueueReply prior = replay(command, fingerprint);
        if (prior != null) return prior;
        try {
            return tx.execute(ignored -> {
                String task = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_dispatch_task (id, engine_run_id, event_id, "
                                + "owner_username, project_id, schedule_id, job_id, job_revision, "
                                + "handler_key, parameters_json, timeout_seconds, max_attempts, "
                                + "attempts, status, lease_epoch, next_attempt_at, payload_fingerprint, "
                                + "created_at, updated_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,'READY',0,?,?,?,?)",
                        task, command.runId(), command.eventId(), command.ownerUsername(), command.projectId(),
                        command.scheduleId(), command.jobId(), command.jobRevision(), command.handlerKey(),
                        command.parametersJson(), command.timeoutSeconds(), command.maxAttempts(),
                        Timestamp.from(Instant.EPOCH), fingerprint, now, now);
                return new DispatchEnqueueReply("CREATED", task, READY);
            });
        } catch (DuplicateKeyException duplicate) {
            DispatchEnqueueReply committed = replay(command, fingerprint);
            return committed == null ? new DispatchEnqueueReply("CONFLICT", null, null) : committed;
        }
    }


    /**
     * 按 NodeRun ID 设置一个持久取消栅栏；无任务时写入不可领取的唯一索引占位行，
     * 从而与异步 enqueue 以 UNIQUE(engine_run_id) 精确竞争，不存在取消后再入队窗口。
     */
    @Override
    public DispatchCancelReply cancelDagTask(DispatchCancelCommand command) {
        if (command == null || command.workflowRevision() < 1) {
            throw new IllegalArgumentException("Invalid DAG cancellation");
        }
        validateUuid(command.runId());
        validateOwner(command.ownerUsername());
        validateUuid(command.projectId());
        validateUuid(command.dagRunId());
        validateUuid(command.workflowId());
        try {
            return persistDagCancel(command);
        } catch (DuplicateKeyException racedEnqueue) {
            // Enqueue committed before the tombstone insert: lock and cancel the real task instead.
            return persistDagCancel(command);
        }
    }

    private DispatchCancelReply persistDagCancel(DispatchCancelCommand command) {
        return tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<CancelRow> rows = jdbc.query(
                    "SELECT id,owner_username,project_id,schedule_id,job_id,job_revision,status "
                            + "FROM flow_dispatch_task WHERE engine_run_id = ? FOR UPDATE",
                    (rs, index) -> new CancelRow(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getInt(6), rs.getString(7)), command.runId());
            if (rows.isEmpty()) {
                String tombstoneId = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO flow_dispatch_task (id,engine_run_id,event_id,"
                                + "owner_username,project_id,schedule_id,job_id,job_revision,"
                                + "handler_key,parameters_json,timeout_seconds,max_attempts,"
                                + "attempts,status,lease_epoch,next_attempt_at,payload_fingerprint,"
                                + "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,"
                                + "'flow.cancelled','{}',1,1,0,'CANCELLED',0,?,?,?,?)",
                        tombstoneId, command.runId(), UUID.randomUUID().toString(),
                        command.ownerUsername(), command.projectId(), command.dagRunId(),
                        command.workflowId(), command.workflowRevision(), Timestamp.from(Instant.EPOCH),
                        cancelFingerprint(command), now, now);
                return new DispatchCancelReply(CANCELLED, CANCELLED);
            }
            CancelRow old = rows.getFirst();
            if (!old.owner().equals(command.ownerUsername())
                    || !old.project().equals(command.projectId())
                    || !old.dagRun().equals(command.dagRunId())
                    || !old.workflow().equals(command.workflowId())
                    || old.revision() != command.workflowRevision()) {
                return new DispatchCancelReply("CONFLICT", null);
            }
            if (SUCCEEDED.equals(old.status()) || FAILED.equals(old.status())) {
                return new DispatchCancelReply("TERMINAL", old.status());
            }
            if (!CANCELLED.equals(old.status())) {
                int updated = jdbc.update("UPDATE flow_dispatch_task SET status='CANCELLED', "
                                + "lease_epoch=lease_epoch+1,lease_token=NULL,worker_id=NULL,"
                                + "lease_until=NULL,updated_at=? WHERE id=? "
                                + "AND status IN ('READY','LEASED')",
                        now, old.id());
                if (updated != 1) throw new IllegalStateException("DAG cancel status CAS failed");
                jdbc.update("UPDATE flow_dispatch_attempt SET status='CANCELLED',finished_at=? "
                                + "WHERE task_id=? AND status='LEASED'",
                        now, old.id());
            }
            return new DispatchCancelReply(CANCELLED, CANCELLED);
        });
    }

    private static String cancelFingerprint(DispatchCancelCommand command) {
        String raw = String.join("\u0000", command.runId(), command.ownerUsername(),
                command.projectId(), command.dagRunId(), command.workflowId(),
                Integer.toString(command.workflowRevision()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private record CancelRow(String id, String owner, String project, String dagRun,
                             String workflow, int revision, String status) { }


    /**
     * Persist a bounded batch only for the current lease generation. A completed attempt may
     * still flush buffered logs, but an expired generation cannot append after a new claim.
     */
    @Override
    public DispatchLogBatchReply appendLogs(DispatchLogBatchCommand command) {
        if (command == null || command.entries() == null || command.entries().length < 1
                || command.entries().length > MAX_LOG_BATCH || command.leaseEpoch() < 1) {
            return new DispatchLogBatchReply("INVALID", 0, 0, 0);
        }
        String worker = validateWorker(command.workerId());
        String task = validateUuid(command.taskId());
        String token = validateUuid(command.leaseToken());
        List<SanitizedLog> sanitized = new ArrayList<>(command.entries().length);
        try {
            for (DispatchLogEntry entry : command.entries()) {
                if (entry == null || entry.sequence() < 1 || entry.level() == null
                        || !List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR").contains(entry.level())) {
                    return new DispatchLogBatchReply("INVALID", 0, 0, command.entries().length);
                }
                String message = sanitizeLogMessage(entry.message());
                String fieldsJson = sanitizeLogFields(entry.fields());
                sanitized.add(new SanitizedLog(entry.sequence(), entry.level(),
                        message.isBlank() ? "(empty log message)" : message, fieldsJson));
            }
        } catch (IllegalArgumentException invalid) {
            return new DispatchLogBatchReply("INVALID", 0, 0, command.entries().length);
        }
        return tx.execute(ignored -> {
            List<LogLeaseRow> leases = jdbc.query(
                    "SELECT t.engine_run_id,t.owner_username,t.project_id,t.lease_epoch,"
                            + "a.worker_id,a.lease_token FROM flow_dispatch_task t "
                            + "JOIN flow_dispatch_attempt a ON a.task_id=t.id AND a.lease_epoch=? "
                            + "WHERE t.id=? FOR UPDATE",
                    (rs, row) -> new LogLeaseRow(rs.getString(1), rs.getString(2),
                            rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)),
                    command.leaseEpoch(), task);
            if (leases.isEmpty()) return new DispatchLogBatchReply("STALE", 0, 0, 0);
            LogLeaseRow lease = leases.getFirst();
            if (lease.epoch() != command.leaseEpoch() || !lease.worker().equals(worker)
                    || !lease.token().equals(token)) {
                return new DispatchLogBatchReply("STALE", 0, 0, 0);
            }
            int currentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flow_dispatch_log WHERE task_id=? AND lease_epoch=?",
                    Integer.class, task, command.leaseEpoch());
            int accepted = 0;
            int duplicates = 0;
            int dropped = 0;
            Timestamp now = dbNow();
            for (SanitizedLog entry : sanitized) {
                int exists = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flow_dispatch_log WHERE task_id=? AND lease_epoch=? AND sequence_no=?",
                        Integer.class, task, command.leaseEpoch(), entry.sequence());
                if (exists > 0) {
                    duplicates++;
                    continue;
                }
                if (currentCount >= MAX_LOGS_PER_ATTEMPT) {
                    dropped++;
                    continue;
                }
                int inserted = jdbc.update("INSERT IGNORE INTO flow_dispatch_log "
                                + "(id,task_id,engine_run_id,owner_username,project_id,lease_epoch,"
                                + "sequence_no,level,message,fields_json,created_at) "
                                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), task, lease.nodeRunId(), lease.owner(),
                        lease.project(), command.leaseEpoch(), entry.sequence(), entry.level(),
                        entry.message(), entry.fieldsJson(), now);
                if (inserted == 1) {
                    accepted++;
                    currentCount++;
                } else {
                    duplicates++;
                }
            }
            return new DispatchLogBatchReply(dropped > 0 ? "LIMIT_REACHED" : "OK",
                    accepted, duplicates, dropped);
        });
    }

    /** Read-only query: callers must first prove the Run belongs to the principal/project. */
    @Override
    public DispatchLogPageView queryLogs(String ownerUsername, String projectId,
            String[] nodeRunIds, String cursor, int limit) {
        String owner = validateOwner(ownerUsername);
        String project = validateUuid(projectId);
        if (limit < 1 || limit > 100 || nodeRunIds == null || nodeRunIds.length > MAX_LOG_QUERY_NODES) {
            throw new IllegalArgumentException("Invalid log query bounds");
        }
        if (nodeRunIds.length == 0) return new DispatchLogPageView(new DispatchLogView[0], null, limit);
        List<String> ids = new ArrayList<>(nodeRunIds.length);
        for (String id : nodeRunIds) {
            String valid = validateUuid(id);
            if (!ids.contains(valid)) ids.add(valid);
        }
        if (ids.isEmpty()) return new DispatchLogPageView(new DispatchLogView[0], null, limit);
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(owner);
        args.add(project);
        args.addAll(ids);
        String cursorClause = "";
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.length() > 512) throw new IllegalArgumentException("Invalid log cursor length");
            LogCursor parsed = decodeLogCursor(cursor);
            cursorClause = " AND (l.created_at < ? OR (l.created_at = ? AND l.id < ?))";
            args.add(parsed.createdAt());
            args.add(parsed.createdAt());
            args.add(parsed.id());
        }
        args.add(limit + 1);
        List<DispatchLogView> rows = jdbc.query(
                "SELECT l.id,l.engine_run_id,l.task_id,l.lease_epoch,l.sequence_no,l.level,"
                        + "l.message,l.fields_json,l.created_at FROM flow_dispatch_log l "
                        + "WHERE l.owner_username=? AND l.project_id=? AND l.engine_run_id IN ("
                        + placeholders + ")" + cursorClause
                        + " ORDER BY l.created_at DESC,l.id DESC LIMIT ?",
                (rs, row) -> new DispatchLogView(rs.getString("id"), rs.getString("engine_run_id"),
                        rs.getString("task_id"), rs.getLong("lease_epoch"), rs.getLong("sequence_no"),
                        rs.getString("level"), rs.getString("message"), rs.getString("fields_json"),
                        rs.getTimestamp("created_at").toInstant().toString()),
                args.toArray());
        String next = null;
        if (rows.size() > limit) {
            rows.remove(rows.size() - 1);
            DispatchLogView last = rows.getLast();
            next = encodeLogCursor(last.createdAt(), last.id());
        }
        return new DispatchLogPageView(rows.toArray(DispatchLogView[]::new), next, limit);
    }

    private static String sanitizeLogMessage(String value) {
        String message = value == null ? "" : value;
        message = SENSITIVE_ASSIGNMENT.matcher(message).replaceAll("$1$2[REDACTED]");
        message = BEARER.matcher(message).replaceAll("Bearer [REDACTED]");
        return truncateUtf8(message, MAX_LOG_MESSAGE_BYTES);
    }

    private String sanitizeLogFields(Map<String, String> fields) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (fields != null) {
            if (fields.size() > MAX_LOG_FIELDS) throw new IllegalArgumentException("Too many log fields");
            for (Map.Entry<String, String> field : fields.entrySet()) {
                String key = field.getKey();
                if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")) {
                    throw new IllegalArgumentException("Invalid log field name");
                }
                String rawValue = (field.getValue() == null ? "" : field.getValue())
                        .replaceAll("\\p{Cntrl}", " ");
                String value = SENSITIVE_FIELD.matcher(key).matches()
                        ? "[REDACTED]" : truncateUtf8(sanitizeLogMessage(rawValue), MAX_LOG_FIELD_VALUE_BYTES);
                safe.put(key, value);
            }
        }
        try {
            String json = mapper.writeValueAsString(safe);
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_LOG_FIELDS_BYTES) {
                throw new IllegalArgumentException("Log fields exceed 4 KiB");
            }
            return json;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Log fields are not JSON serializable", invalid);
        }
    }

    private static String truncateUtf8(String value, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String part = new String(Character.toChars(codePoint));
            int size = part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) break;
            result.append(part);
            bytes += size;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private static String encodeLogCursor(String createdAt, String id) {
        String value = createdAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static LogCursor decodeLogCursor(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int delimiter = decoded.indexOf('|');
            if (delimiter < 1 || delimiter == decoded.length() - 1) {
                throw new IllegalArgumentException("Invalid log cursor");
            }
            return new LogCursor(Timestamp.from(Instant.parse(decoded.substring(0, delimiter))),
                    validateUuid(decoded.substring(delimiter + 1)));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid log cursor", invalid);
        }
    }

    private record LogLeaseRow(String nodeRunId, String owner, String project,
            long epoch, String worker, String token) { }
    private record SanitizedLog(long sequence, String level, String message, String fieldsJson) { }
    private record LogCursor(Timestamp createdAt, String id) { }

    /** 仅读取租户拥有的任务元数据；对浏览器不暴露 leaseToken。 */
    @Override
    public DispatchTaskListReply list(String ownerUsername, String projectId) {
        String owner = validateOwner(ownerUsername);
        String project = validateUuid(projectId);
        List<DispatchTaskView> tasks = jdbc.query("SELECT * FROM flow_dispatch_task "
                        + "WHERE owner_username = ? AND project_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, index) -> readView(rs), owner, project, LIST_LIMIT);
        return new DispatchTaskListReply(tasks.toArray(DispatchTaskView[]::new));
    }

    /** 单次最多领取八个同 Handler 的任务；领取和更新租约均发生在短事务内。 */
    @Override
    public DispatchLeaseReply claim(DispatchLeaseCommand command) {
        return claimInternal(command, null);
    }

    /**
     * 仅供 e2e Profile 的 HTTP Probe 精确领取指定 NodeRun。
     * 不属于 DispatcherRpc，不改变生产 Worker 的公平扫描接口。
     */
    DispatchLeaseReply claimForE2eTarget(DispatchLeaseCommand command, String engineRunId) {
        return claimInternal(command, validateUuid(engineRunId));
    }

    private DispatchLeaseReply claimInternal(DispatchLeaseCommand command, String targetRunId) {
        if (command == null || command.limit() < 1 || command.limit() > CLAIM_LIMIT) {
            throw new IllegalArgumentException("Invalid dispatch claim limit");
        }
        String worker = validateWorker(command.workerId());
        String handler = validateHandler(command.handlerKey());
        DispatchLeaseReply reply = tx.execute(ignored -> {
            Timestamp now = dbNow();
            // Keep production SQL unchanged; append a bound NodeRun criterion only for CI.
            String query = "SELECT * FROM flow_dispatch_task "
                    + "WHERE handler_key = ? AND attempts < max_attempts AND "
                    + "((status = 'READY' AND next_attempt_at <= ?) OR "
                    + "(status = 'LEASED' AND lease_until <= ?)) "
                    + (targetRunId == null ? "" : "AND engine_run_id = ? ")
                    + "ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED";
            Object[] args = targetRunId == null
                    ? new Object[]{handler, now, now, command.limit()}
                    : new Object[]{handler, now, now, targetRunId, command.limit()};
            List<TaskRow> rows = jdbc.query(query, (rs, index) -> readTask(rs), args);
            List<DispatchLeaseView> claims = new ArrayList<>(rows.size());
            for (TaskRow row : rows) {
                if (LEASED.equals(row.status())) {
                    jdbc.update("UPDATE flow_dispatch_attempt SET status = 'EXPIRED', finished_at = ? "
                                    + "WHERE task_id = ? AND lease_epoch = ? AND status = 'LEASED'",
                            now, row.id(), row.epoch());
                }
                long epoch = row.epoch() + 1;
                String token = UUID.randomUUID().toString();
                Timestamp leaseUntil = Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS));
                int changed = jdbc.update("UPDATE flow_dispatch_task SET status = 'LEASED', "
                                + "lease_epoch = ?, lease_token = ?, worker_id = ?, lease_until = ?, "
                                + "attempts = attempts + 1, updated_at = ? "
                                + "WHERE id = ? AND lease_epoch = ? AND status = ? AND attempts < max_attempts",
                        epoch, token, worker, leaseUntil, now, row.id(), row.epoch(), row.status());
                if (changed != 1) throw new IllegalStateException("Dispatcher claim CAS lost");
                jdbc.update("INSERT INTO flow_dispatch_attempt "
                                + "(task_id, lease_epoch, worker_id, lease_token, status, started_at) "
                                + "VALUES (?, ?, ?, ?, 'LEASED', ?)", row.id(), epoch, worker, token, now);
                claims.add(new DispatchLeaseView(row.id(), row.runId(), row.eventId(), row.project(),
                        row.handler(), row.parametersJson(), row.timeoutSeconds(), row.attempts() + 1,
                        row.maxAttempts(), epoch, token, leaseUntil.toInstant().toString()));
            }
            return new DispatchLeaseReply(claims.toArray(DispatchLeaseView[]::new));
        });
        return reply == null ? new DispatchLeaseReply(new DispatchLeaseView[0]) : reply;
    }

    /** 心跳只允许合法且未过期的当前代际延长租约。 */
    @Override
    public DispatchMutationReply heartbeat(DispatchHeartbeatCommand command) {
        if (command == null || command.leaseEpoch() < 1) throw new IllegalArgumentException("Invalid heartbeat");
        String worker = validateWorker(command.workerId());
        String task = validateUuid(command.taskId());
        String token = validateUuid(command.leaseToken());
        Timestamp now = dbNow();
        int changed = jdbc.update("UPDATE flow_dispatch_task SET lease_until = ?, updated_at = ? "
                        + "WHERE id = ? AND status = 'LEASED' AND worker_id = ? "
                        + "AND lease_epoch = ? AND lease_token = ? AND lease_until > ?",
                Timestamp.from(now.toInstant().plusSeconds(LEASE_SECONDS)), now,
                task, worker, command.leaseEpoch(), token, now);
        return changed == 1 ? new DispatchMutationReply("OK", LEASED)
                : new DispatchMutationReply(STALE, null);
    }

    /** 原子完成：过期代际不能置终态，也不能把新尝试的结果覆盖。 */
    @Override
    public DispatchMutationReply complete(DispatchCompleteCommand command) {
        if (command == null || command.leaseEpoch() < 1
                || !(SUCCEEDED.equals(command.outcome()) || FAILED.equals(command.outcome()))) {
            throw new IllegalArgumentException("Invalid dispatch completion");
        }
        String worker = validateWorker(command.workerId());
        String task = validateUuid(command.taskId());
        String token = validateUuid(command.leaseToken());
        return tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<int[]> attempts = jdbc.query("SELECT attempts, max_attempts FROM flow_dispatch_task "
                            + "WHERE id = ? AND status = 'LEASED' AND worker_id = ? "
                            + "AND lease_epoch = ? AND lease_token = ? AND lease_until > ? FOR UPDATE",
                    (rs, idx) -> new int[]{rs.getInt("attempts"), rs.getInt("max_attempts")},
                    task, worker, command.leaseEpoch(), token, now);
            if (attempts.isEmpty()) return new DispatchMutationReply(STALE, null);
            boolean retry = FAILED.equals(command.outcome()) && attempts.getFirst()[0] < attempts.getFirst()[1];
            String nextStatus = retry ? READY : command.outcome();
            Timestamp nextAt = Timestamp.from(now.toInstant().plusSeconds(
                    Math.min(1L << Math.min(attempts.getFirst()[0], 5), 30L)));
            int changed = jdbc.update("UPDATE flow_dispatch_task SET status = ?, "
                            + "lease_token = NULL, worker_id = NULL, lease_until = NULL, "
                            + "next_attempt_at = ?, updated_at = ? "
                            + "WHERE id = ? AND status = 'LEASED' AND lease_epoch = ? AND lease_token = ? "
                            + "AND lease_until > ?",
                    nextStatus, nextAt, now, task, command.leaseEpoch(), token, now);
            if (changed != 1) return new DispatchMutationReply(STALE, null);
            int recorded = jdbc.update("UPDATE flow_dispatch_attempt SET status = ?, finished_at = ? "
                            + "WHERE task_id = ? AND lease_epoch = ? AND lease_token = ? AND status = 'LEASED'",
                    command.outcome(), now, task, command.leaseEpoch(), token);
            if (recorded != 1) throw new IllegalStateException("Attempt record missing");
            if (SUCCEEDED.equals(nextStatus) || FAILED.equals(nextStatus)) {
                stageOutcome(task, command.leaseEpoch(), nextStatus, now);
            }
            return new DispatchMutationReply("OK", nextStatus);
        });
    }

    /** 有限过期回收；最后一次失败的租约变为 FAILED，非最后一次可重新领取。 */
    public int expireExhaustedOnce() {
        Integer expired = tx.execute(ignored -> {
            Timestamp now = dbNow();
            List<TaskRow> rows = jdbc.query("SELECT * FROM flow_dispatch_task "
                            + "WHERE status = 'LEASED' AND lease_until <= ? AND attempts >= max_attempts "
                            + "ORDER BY lease_until, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    (rs, idx) -> readTask(rs), now, REAPER_LIMIT);
            for (TaskRow row : rows) {
                int changed = jdbc.update("UPDATE flow_dispatch_task SET status = 'FAILED', "
                                + "lease_token = NULL, worker_id = NULL, lease_until = NULL, updated_at = ? "
                                + "WHERE id = ? AND status = 'LEASED' AND lease_epoch = ? AND lease_until <= ?",
                        now, row.id(), row.epoch(), now);
                if (changed != 1) throw new IllegalStateException("Expired lease fencing mismatch");
                jdbc.update("UPDATE flow_dispatch_attempt SET status = 'EXPIRED', finished_at = ? "
                                + "WHERE task_id = ? AND lease_epoch = ? AND status = 'LEASED'",
                        now, row.id(), row.epoch());
                stageOutcome(row.id(), row.epoch(), FAILED, now);
            }
            return rows.size();
        });
        return expired == null ? 0 : expired;
    }


    /** 将终态任务作为唯一、持久的 Engine 结果事件写入 Dispatcher 自有事务。 */
    private void stageOutcome(String taskId, long epoch, String outcome, Timestamp now) {
        int inserted = jdbc.update("INSERT INTO flow_dispatch_outcome_outbox "
                        + "(event_id,engine_run_id,task_id,project_id,lease_epoch,outcome,"
                        + "delivery_state,attempts,created_at) "
                        + "SELECT ?,engine_run_id,id,project_id,?,?,'PENDING',0,? "
                        + "FROM flow_dispatch_task WHERE id = ?",
                UUID.randomUUID().toString(), epoch, outcome, now, taskId);
        if (inserted != 1) throw new IllegalStateException("No dispatch task for terminal outcome");
    }

    private DispatchEnqueueReply replay(DispatchEnqueueCommand c, String fingerprint) {
        List<DispatchEnqueueReply> results = jdbc.query("SELECT id, status, payload_fingerprint "
                        + "FROM flow_dispatch_task WHERE engine_run_id = ?",
                (rs, idx) -> CANCELLED.equals(rs.getString("status"))
                        ? new DispatchEnqueueReply("CANCELLED", rs.getString("id"), CANCELLED)
                        : fingerprint.equals(rs.getString("payload_fingerprint"))
                        ? new DispatchEnqueueReply("DUPLICATE", rs.getString("id"), rs.getString("status"))
                        : new DispatchEnqueueReply("CONFLICT", null, null), c.runId());
        return results.isEmpty() ? null : results.getFirst();
    }

    private Timestamp dbNow() {
        Timestamp clock = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (clock == null) throw new IllegalStateException("Database clock unavailable");
        return clock;
    }

    private void validateEnqueue(DispatchEnqueueCommand c) {
        if (c == null || c.jobRevision() < 1 || c.timeoutSeconds() < 1 || c.timeoutSeconds() > 86400
                || c.maxAttempts() < 1 || c.maxAttempts() > 10) {
            throw new IllegalArgumentException("Invalid dispatch task limits");
        }
        validateUuid(c.runId());
        validateUuid(c.eventId());
        validateOwner(c.ownerUsername());
        validateUuid(c.projectId());
        validateUuid(c.scheduleId());
        validateUuid(c.jobId());
        validateHandler(c.handlerKey());
        if (c.parametersJson() == null || c.parametersJson().getBytes(StandardCharsets.UTF_8).length > MAX_PARAMETERS_BYTES) {
            throw new IllegalArgumentException("Invalid dispatch parameters length");
        }
        try {
            JsonNode json = mapper.readTree(c.parametersJson());
            if (json == null || !json.isObject()) throw new IllegalArgumentException("Expected JSON parameters object");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Malformed JSON parameters", invalid);
        }
    }

    private static String fingerprint(DispatchEnqueueCommand c) {
        String payload = String.join("\u0000", c.runId(), c.eventId(), c.ownerUsername(), c.projectId(),
                c.scheduleId(), c.jobId(), String.valueOf(c.jobRevision()), c.handlerKey(),
                c.parametersJson(), String.valueOf(c.timeoutSeconds()), String.valueOf(c.maxAttempts()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 missing", impossible);
        }
    }

    private static String validateOwner(String owner) {
        if (owner == null || !OWNER.matcher(owner).matches()) throw new IllegalArgumentException("Invalid owner");
        return owner;
    }
    private static String validateWorker(String worker) {
        if (worker == null || !WORKER.matcher(worker).matches()) throw new IllegalArgumentException("Invalid worker identifier");
        return worker;
    }
    private static String validateHandler(String handler) {
        if (handler == null || !HANDLER.matcher(handler).matches()) throw new IllegalArgumentException("Invalid handler key");
        return handler;
    }
    private static String validateUuid(String id) {
        if (id == null) throw new IllegalArgumentException("Missing UUID");
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Noncanonical UUID");
            return id;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid UUID");
        }
    }

    private static DispatchTaskView readView(ResultSet rs) throws SQLException {
        Timestamp leaseUntil = rs.getTimestamp("lease_until");
        return new DispatchTaskView(rs.getString("id"), rs.getString("engine_run_id"),
                rs.getString("project_id"), rs.getString("handler_key"), rs.getString("status"),
                rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getLong("lease_epoch"),
                leaseUntil == null ? null : leaseUntil.toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString());
    }
    private static TaskRow readTask(ResultSet rs) throws SQLException {
        return new TaskRow(rs.getString("id"), rs.getString("engine_run_id"), rs.getString("event_id"),
                rs.getString("project_id"), rs.getString("handler_key"), rs.getString("parameters_json"),
                rs.getInt("timeout_seconds"), rs.getInt("attempts"), rs.getInt("max_attempts"),
                rs.getLong("lease_epoch"), rs.getString("status"));
    }
    private record TaskRow(String id, String runId, String eventId, String project, String handler,
                           String parametersJson, int timeoutSeconds, int attempts, int maxAttempts,
                           long epoch, String status) { }
}
