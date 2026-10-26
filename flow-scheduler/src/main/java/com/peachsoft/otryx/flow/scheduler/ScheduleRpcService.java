package com.peachsoft.otryx.flow.scheduler;

import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleCreateCommand;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleListReply;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleMutationReply;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleToggleCommand;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleTriggerListReply;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleTriggerView;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleView;
import com.peachsoft.otryx.spring.annotation.OtryxRpcReference;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 基于 OTRYX RPC 的持久化计划 Provider；创建前验证 Metadata 不可变 JobRevision。 */
@OtryxRpcService(interfaceClass = ScheduleRpc.class, version = "1.0.0")
public class ScheduleRpcService implements ScheduleRpc {
    private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern REQUEST_KEY = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private static final int MAX_ROWS = 100;
    private static final String OK = "OK";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String CONFLICT = "CONFLICT";

    @OtryxRpcReference(version = "1.0.0")
    private JobRpc jobs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SchedulePlanner planner = new SchedulePlanner();

    /** 创建 Provider，所有事务仅触及 Scheduler 自己拥有的数据库。 */
    public ScheduleRpcService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
    }

    @Override
    public ScheduleMutationReply create(ScheduleCreateCommand command) {
        if (command == null) throw new IllegalArgumentException("Missing schedule command");
        String owner = owner(command.ownerUsername());
        String project = uuid(command.projectId());
        String job = uuid(command.jobId());
        String key = key(command.requestKey());
        if (command.jobRevision() < 1) throw new IllegalArgumentException("Unpublished job revision");
        SchedulePlanner.MisfirePolicy.valueOf(command.misfirePolicy());
        // Schedule from the database time; next UTC cursor is deterministic for the identical input.
        Instant next = planner.next(command.expression(), command.timeZone(), Instant.now());
        String fingerprint = fingerprint(project, job, command);
        ScheduleView prior = existingByKey(owner, key);
        if (prior != null) return replay(prior, project, job, command, fingerprint);
        // Cross-service auth is performed via a bounded RPC outside any Scheduler DB transaction.
        if (jobs.revision(owner, project, job, command.jobRevision()) == null) {
            return reply(NOT_FOUND, null, false);
        }
        try {
            return tx.execute(ignored -> {
                String id = UUID.randomUUID().toString();
                Timestamp now = Timestamp.from(Instant.now());
                jdbc.update("INSERT INTO flow_schedule_definition (id, project_id, owner_username, "
                                + "create_request_key, create_fingerprint, job_id, job_revision, schedule_revision, "
                                + "cron_expr, time_zone, misfire_policy, enabled, next_fire_at_utc, "
                                + "version, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, 1, ?, 1, ?, ?)",
                        id, project, owner, key, fingerprint, job, command.jobRevision(),
                        command.expression().trim(), command.timeZone(), command.misfirePolicy(),
                        Timestamp.from(next), now, now);
                return reply(OK, owned(owner, project, id), false);
            });
        } catch (DuplicateKeyException duplicate) {
            ScheduleView concurrent = existingByKey(owner, key);
            if (concurrent == null) throw duplicate;
            return replay(concurrent, project, job, command, fingerprint);
        }
    }

    @Override
    public ScheduleListReply list(String ownerUsername, String projectId) {
        String owner = owner(ownerUsername);
        String project = uuid(projectId);
        List<ScheduleView> rows = jdbc.query("SELECT * FROM flow_schedule_definition "
                        + "WHERE owner_username = ? AND project_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, index) -> readSchedule(rs), owner, project, MAX_ROWS);
        return new ScheduleListReply(rows.toArray(ScheduleView[]::new));
    }

    @Override
    public ScheduleTriggerListReply triggers(String ownerUsername, String projectId, String scheduleId) {
        String owner = owner(ownerUsername);
        String project = uuid(projectId);
        String id = uuid(scheduleId);
        List<ScheduleTriggerView> rows = jdbc.query("SELECT t.* FROM flow_schedule_trigger t "
                        + "JOIN flow_schedule_definition s ON s.id = t.schedule_id "
                        + "WHERE s.id = ? AND s.owner_username = ? AND s.project_id = ? "
                        + "ORDER BY t.planned_fire_at_utc DESC LIMIT ?",
                (rs, index) -> new ScheduleTriggerView(rs.getString("id"), rs.getString("schedule_id"),
                        rs.getInt("schedule_revision"), rs.getTimestamp("planned_fire_at_utc").toInstant().toString(),
                        rs.getString("status"), rs.getTimestamp("created_at").toInstant().toString()),
                id, owner, project, MAX_ROWS);
        return new ScheduleTriggerListReply(rows.toArray(ScheduleTriggerView[]::new));
    }

    @Override
    public ScheduleMutationReply toggle(ScheduleToggleCommand command) {
        if (command == null || command.expectedVersion() < 1) throw new IllegalArgumentException("Invalid schedule toggle");
        String owner = owner(command.ownerUsername());
        String project = uuid(command.projectId());
        String id = uuid(command.scheduleId());
        return tx.execute(ignored -> {
            ScheduleView current = owned(owner, project, id);
            if (current == null) return reply(NOT_FOUND, null, false);
            if (current.version() != command.expectedVersion()) return reply(CONFLICT, current, false);
            if (current.enabled() == command.enabled()) return reply(OK, current, true);
            Instant next = command.enabled()
                    ? planner.next(current.expression(), current.timeZone(), Instant.now())
                    : Instant.parse(current.nextFireUtc());
            int updated = jdbc.update("UPDATE flow_schedule_definition "
                            + "SET enabled = ?, next_fire_at_utc = ?, version = version + 1, "
                            + "updated_at = CURRENT_TIMESTAMP(3) "
                            + "WHERE id = ? AND owner_username = ? AND project_id = ? AND version = ?",
                    command.enabled() ? 1 : 0, Timestamp.from(next), id, owner, project,
                    command.expectedVersion());
            if (updated != 1) return reply(CONFLICT, owned(owner, project, id), false);
            return reply(OK, owned(owner, project, id), false);
        });
    }

    private ScheduleMutationReply replay(ScheduleView schedule, String project, String job,
            ScheduleCreateCommand requested, String fingerprint) {
        String original = jdbc.queryForObject("SELECT create_fingerprint FROM flow_schedule_definition WHERE id = ?",
                String.class, schedule.id());
        return project.equals(schedule.projectId()) && job.equals(schedule.jobId())
                && requested.jobRevision() == schedule.jobRevision() && fingerprint.equals(original)
                ? reply(OK, schedule, true) : reply(CONFLICT, schedule, true);
    }

    private ScheduleView existingByKey(String owner, String key) {
        List<ScheduleView> rows = jdbc.query("SELECT * FROM flow_schedule_definition "
                        + "WHERE owner_username = ? AND create_request_key = ?",
                (rs, index) -> readSchedule(rs), owner, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ScheduleView owned(String owner, String project, String id) {
        List<ScheduleView> rows = jdbc.query("SELECT * FROM flow_schedule_definition "
                        + "WHERE id = ? AND owner_username = ? AND project_id = ?",
                (rs, index) -> readSchedule(rs), id, owner, project);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static ScheduleView readSchedule(ResultSet rs) throws SQLException {
        return new ScheduleView(rs.getString("id"), rs.getString("project_id"),
                rs.getString("job_id"), rs.getInt("job_revision"), rs.getString("cron_expr"),
                rs.getString("time_zone"), rs.getString("misfire_policy"), rs.getBoolean("enabled"),
                rs.getLong("version"), rs.getTimestamp("next_fire_at_utc").toInstant().toString());
    }

    private static ScheduleMutationReply reply(String code, ScheduleView schedule, boolean duplicate) {
        return new ScheduleMutationReply(code, schedule, duplicate);
    }

    private static String owner(String username) {
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid schedule owner");
        }
        return username;
    }

    private static String uuid(String input) {
        try {
            if (input == null) throw new IllegalArgumentException("Missing schedule identifier");
            return UUID.fromString(input).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid schedule identifier");
        }
    }

    private static String key(String value) {
        if (value == null || !REQUEST_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid schedule request key");
        }
        return value;
    }

    private static String fingerprint(String project, String job, ScheduleCreateCommand c) {
        try {
            String data = String.join("\u0000", project, job, Integer.toString(c.jobRevision()),
                    c.expression().trim(), c.timeZone(), c.misfirePolicy());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 missing", unavailable);
        }
    }
}
