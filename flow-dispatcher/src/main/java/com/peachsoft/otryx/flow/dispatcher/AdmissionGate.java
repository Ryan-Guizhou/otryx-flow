package com.peachsoft.otryx.flow.dispatcher;

import com.peachsoft.otryx.flow.contracts.v1.DispatchEnqueueCommand;
import com.peachsoft.otryx.flow.contracts.v1.ProjectAdmissionCommand;
import com.peachsoft.otryx.flow.contracts.v1.ProjectAdmissionView;
import com.peachsoft.otryx.flow.contracts.v1.WorkerDrainCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkerDrainView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 项目入队配额、并发策略和 Worker 停领窗口。
 *
 * <p>只读写 Dispatcher 自有表。缺少配置行时使用有界默认值；计数查询失败时由调用方拒绝入队。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
final class AdmissionGate {
    static final int DEFAULT_MAX_INFLIGHT = 64;
    static final int DEFAULT_MAX_QUEUED = 256;
    static final int DEFAULT_RETENTION_DAYS = 14;
    static final String CAPACITY_REJECTED = "CAPACITY_REJECTED";
    static final String CAPACITY_WAIT = "CAPACITY_WAIT";
    private static final Pattern POLICY = Pattern.compile("ALLOW|FORBID|QUEUE");
    private static final Pattern WORKER = Pattern.compile("[a-zA-Z0-9_.-]{3,64}");
    private static final Pattern OWNER = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");

    private AdmissionGate() { }

    /**
     * 在同一事务内判断这次入队是否会超过项目配额。
     *
     * @param jdbc Dispatcher 数据源
     * @param command 已经通过基础校验的入队命令
     * @return 拒绝码；允许入队时返回 null
     */
    static String rejectEnqueue(JdbcTemplate jdbc, DispatchEnqueueCommand command) {
        jdbc.query("SELECT project_id FROM flow_project_admission WHERE project_id=? FOR UPDATE",
                (rs, index) -> rs.getString(1), command.projectId());
        Limits limits = load(jdbc, command.projectId());
        int sameJob = count(jdbc, "SELECT COUNT(*) FROM flow_dispatch_task WHERE project_id=? "
                + "AND job_id=? AND target_type='JOB' AND status IN ('READY','LEASED')",
                command.projectId(), command.jobId());
        if ("JOB".equals(command.targetType()) && "FORBID".equals(limits.policy) && sameJob > 0) {
            return CAPACITY_WAIT;
        }
        int active = count(jdbc, "SELECT COUNT(*) FROM flow_dispatch_task WHERE project_id=? "
                + "AND status IN ('READY','LEASED')", command.projectId());
        if ("ALLOW".equals(limits.policy) && active >= limits.maxInflight) return CAPACITY_WAIT;
        if (active >= limits.maxQueued) return CAPACITY_REJECTED;
        return null;
    }

    /** 未过期的停领记录使该 Worker 不能领取新任务。 */
    static boolean workerDraining(JdbcTemplate jdbc, String workerId, Timestamp now) {
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_worker_drain WHERE worker_id=? AND drain_until>?",
                Integer.class, workerId, now);
        return active != null && active > 0;
    }

    /** 未过期租约达到上限时，不再把新的 READY 任务发给 Worker。过期租约不占用名额。 */
    static boolean liveInflightAllows(JdbcTemplate jdbc, String projectId, Timestamp now) {
        Limits limits = load(jdbc, projectId);
        int live = count(jdbc, "SELECT COUNT(*) FROM flow_dispatch_task WHERE project_id=? "
                + "AND status='LEASED' AND lease_until>?", projectId, now);
        return live < limits.maxInflight;
    }

    static ProjectAdmissionView get(JdbcTemplate jdbc, String owner, String projectId) {
        Limits limits = load(jdbc, projectId);
        String storedOwner = jdbc.query(
                "SELECT owner_username FROM flow_project_admission WHERE project_id=?",
                (rs, index) -> rs.getString(1), projectId).stream().findFirst().orElse(owner);
        if (!storedOwner.equals(owner)) {
            throw new IllegalArgumentException("Admission owner mismatch");
        }
        return new ProjectAdmissionView(owner, projectId, limits.maxInflight, limits.maxQueued,
                limits.policy, limits.retentionDays);
    }

    static ProjectAdmissionView put(JdbcTemplate jdbc, ProjectAdmissionCommand command, Timestamp now) {
        if (command == null || command.ownerUsername() == null
                || !OWNER.matcher(command.ownerUsername()).matches()
                || command.maxInflight() < 1 || command.maxInflight() > 256
                || command.maxQueued() < command.maxInflight() || command.maxQueued() > 1024
                || command.concurrencyPolicy() == null
                || !POLICY.matcher(command.concurrencyPolicy()).matches()
                || command.retentionDays() < 1 || command.retentionDays() > 3650) {
            throw new IllegalArgumentException("Invalid project admission");
        }
        int updated = jdbc.update("UPDATE flow_project_admission SET owner_username=?, max_inflight=?, "
                        + "max_queued=?, concurrency_policy=?, retention_days=?, updated_at=? "
                        + "WHERE project_id=?",
                command.ownerUsername(), command.maxInflight(), command.maxQueued(),
                command.concurrencyPolicy(), command.retentionDays(), now, command.projectId());
        if (updated == 0) {
            jdbc.update("INSERT INTO flow_project_admission (project_id, owner_username, max_inflight, "
                            + "max_queued, concurrency_policy, retention_days, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?)",
                    command.projectId(), command.ownerUsername(), command.maxInflight(),
                    command.maxQueued(), command.concurrencyPolicy(), command.retentionDays(), now);
        }
        return get(jdbc, command.ownerUsername(), command.projectId());
    }

    static WorkerDrainView drain(JdbcTemplate jdbc, WorkerDrainCommand command, Timestamp now) {
        if (command == null || command.workerId() == null || !WORKER.matcher(command.workerId()).matches()
                || command.drainSeconds() < 1 || command.drainSeconds() > 3600) {
            throw new IllegalArgumentException("Invalid worker drain");
        }
        Timestamp until = Timestamp.from(now.toInstant().plusSeconds(command.drainSeconds()));
        int updated = jdbc.update("UPDATE flow_worker_drain SET drain_until=?, updated_at=? WHERE worker_id=?",
                until, now, command.workerId());
        if (updated == 0) {
            jdbc.update("INSERT INTO flow_worker_drain (worker_id, drain_until, updated_at) VALUES (?,?,?)",
                    command.workerId(), until, now);
        }
        return new WorkerDrainView(command.workerId(), until.toInstant().toString());
    }

    static WorkerDrainView[] activeDrains(JdbcTemplate jdbc, Timestamp now) {
        List<WorkerDrainView> rows = jdbc.query(
                "SELECT worker_id, drain_until FROM flow_worker_drain WHERE drain_until>? "
                        + "ORDER BY worker_id",
                (rs, index) -> new WorkerDrainView(rs.getString(1),
                        rs.getTimestamp(2).toInstant().toString()), now);
        return rows.toArray(WorkerDrainView[]::new);
    }

    /**
     * 删除已终态且结果已投递的任务，跳过 READY、LEASED 和未投递 Outbox。
     *
     * @return 本批删除的任务数，最多 100
     */
    static int purgeTerminal(JdbcTemplate jdbc, Instant now) {
        List<Candidate> rows = jdbc.query(
                "SELECT id, project_id, updated_at FROM flow_dispatch_task "
                        + "WHERE status IN ('SUCCEEDED','FAILED','CANCELLED') ORDER BY updated_at LIMIT 100",
                (rs, index) -> new Candidate(rs.getString(1), rs.getString(2), rs.getTimestamp(3)));
        int deleted = 0;
        for (Candidate row : rows) {
            int days = load(jdbc, row.projectId).retentionDays;
            if (row.updatedAt.toInstant().isAfter(now.minusSeconds(days * 86400L))) continue;
            Integer pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flow_dispatch_outcome_outbox WHERE task_id=? "
                            + "AND delivery_state<>'DELIVERED'", Integer.class, row.id);
            if (pending != null && pending > 0) continue;
            jdbc.update("DELETE FROM flow_dispatch_log WHERE task_id=?", row.id);
            jdbc.update("DELETE FROM flow_dispatch_attempt WHERE task_id=?", row.id);
            jdbc.update("DELETE FROM flow_dispatch_outcome_outbox WHERE task_id=?", row.id);
            deleted += jdbc.update("DELETE FROM flow_dispatch_task WHERE id=? "
                    + "AND status IN ('SUCCEEDED','FAILED','CANCELLED')", row.id);
        }
        return deleted;
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        if (value == null) throw new IllegalStateException("Admission count failed");
        return value;
    }

    private static Limits load(JdbcTemplate jdbc, String projectId) {
        List<Limits> rows = jdbc.query(
                "SELECT max_inflight, max_queued, concurrency_policy, retention_days "
                        + "FROM flow_project_admission WHERE project_id=?",
                (rs, index) -> new Limits(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getInt(4)),
                projectId);
        return rows.isEmpty()
                ? new Limits(DEFAULT_MAX_INFLIGHT, DEFAULT_MAX_QUEUED, "ALLOW", DEFAULT_RETENTION_DAYS)
                : rows.getFirst();
    }

    private record Limits(int maxInflight, int maxQueued, String policy, int retentionDays) { }

    private record Candidate(String id, String projectId, Timestamp updatedAt) { }
}
