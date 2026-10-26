package com.peachsoft.otryx.flow.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.peachsoft.otryx.flow.contracts.v1.JobRevisionView;
import com.peachsoft.otryx.flow.contracts.v1.JobRpc;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleCreateCommand;
import com.peachsoft.otryx.flow.contracts.v1.ScheduleToggleCommand;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** 真实事务库下校验请求键去重、权限边界和版本 CAS。 */
class ScheduleRpcServiceTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String JOB = "00000000-0000-4000-8000-000000000002";
    private ScheduleRpcService service;
    private JdbcTemplate jdbc;
    private JobRpc jobs;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:schedrpc-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_schedule_definition (id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36), "
                + "owner_username VARCHAR(64), create_request_key VARCHAR(80), create_fingerprint CHAR(64), "
                + "job_id VARCHAR(36), job_revision INT, schedule_revision INT, cron_expr VARCHAR(120), "
                + "time_zone VARCHAR(64), misfire_policy VARCHAR(16), enabled BOOLEAN, "
                + "next_fire_at_utc TIMESTAMP(3), version BIGINT, created_at TIMESTAMP(3), "
                + "updated_at TIMESTAMP(3), UNIQUE(owner_username, create_request_key))");
        jdbc.execute("CREATE TABLE flow_schedule_trigger (id VARCHAR(36) PRIMARY KEY, schedule_id VARCHAR(36), "
                + "schedule_revision INT, planned_fire_at_utc TIMESTAMP(3), origin VARCHAR(16), "
                + "status VARCHAR(24), created_at TIMESTAMP(3))");
        service = new ScheduleRpcService(jdbc, new DataSourceTransactionManager(source));
        jobs = mock(JobRpc.class);
        when(jobs.revision("admin", PROJECT, JOB, 1)).thenReturn(
                new JobRevisionView(JOB, 1, "Safe job", "safe.echo", "{}", 30, 2, 1L,
                        "2026-10-26T00:00:00Z"));
        ReflectionTestUtils.setField(service, "jobs", jobs);
    }

    @Test
    void idempotentCreateAndConflictingReplay() {
        String key = UUID.randomUUID().toString();
        var first = service.create(command("admin", key, "FIRE_ONCE"));
        assertEquals("OK", first.code());
        assertFalse(first.duplicate());
        var same = service.create(command("admin", key, "FIRE_ONCE"));
        assertEquals(first.schedule().id(), same.schedule().id());
        assertTrue(same.duplicate());
        assertEquals("CONFLICT", service.create(command("admin", key, "SKIP")).code());
        assertEquals(1, service.list("admin", PROJECT).schedules().length);
        verify(jobs, times(1)).revision("admin", PROJECT, JOB, 1);
    }

    @Test
    void rejectsUnpublishedJobAndOtherOwnerDoesNotSeeSchedule() {
        var created = service.create(command("admin", UUID.randomUUID().toString(), "SKIP"));
        assertEquals("OK", created.code());
        assertEquals(0, service.list("other", PROJECT).schedules().length);
        assertEquals(0, service.triggers("other", PROJECT, created.schedule().id()).triggers().length);
        assertEquals("NOT_FOUND", service.create(command("other", UUID.randomUUID().toString(), "SKIP")).code());
    }

    @Test
    void pauseAndResumeUseOptimisticCas() {
        var first = service.create(command("admin", UUID.randomUUID().toString(), "CATCH_UP")).schedule();
        var paused = service.toggle(new ScheduleToggleCommand("admin", PROJECT, first.id(), first.version(), false));
        assertEquals("OK", paused.code());
        assertFalse(paused.schedule().enabled());
        assertEquals(first.version()+1, paused.schedule().version());
        assertEquals("CONFLICT", service.toggle(new ScheduleToggleCommand("admin", PROJECT, first.id(),
                first.version(), true)).code());
        var resumed = service.toggle(new ScheduleToggleCommand("admin", PROJECT, first.id(),
                paused.schedule().version(), true));
        assertTrue(resumed.schedule().enabled());
        assertEquals(paused.schedule().version()+1, resumed.schedule().version());
    }

    private static ScheduleCreateCommand command(String owner, String key, String misfire) {
        return new ScheduleCreateCommand(owner, PROJECT, JOB, 1, "*/5 * * * * *", "Etc/UTC", misfire, key);
    }
}
