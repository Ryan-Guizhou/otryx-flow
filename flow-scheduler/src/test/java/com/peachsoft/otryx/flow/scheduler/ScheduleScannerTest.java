package com.peachsoft.otryx.flow.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 两个独立 Scanner 对同一共享数据库抢占同一个到期游标，最多写入一个逻辑火点。 */
class ScheduleScannerTest {
    @Test
    void duplicateScannersCommitSingleTriggerAndMatchingOutbox() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:scan-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_schedule_definition (id VARCHAR(36) PRIMARY KEY, "
                + "owner_username VARCHAR(64), project_id VARCHAR(36), "
                + "job_id VARCHAR(36), job_revision INT, schedule_revision INT, cron_expr VARCHAR(120), "
                + "time_zone VARCHAR(64), misfire_policy VARCHAR(16), enabled BOOLEAN, "
                + "next_fire_at_utc TIMESTAMP(3), version BIGINT, updated_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_schedule_trigger (id VARCHAR(36) PRIMARY KEY, schedule_id VARCHAR(36), "
                + "schedule_revision INT, planned_fire_at_utc TIMESTAMP(3), origin VARCHAR(16), "
                + "status VARCHAR(24), created_at TIMESTAMP(3), "
                + "UNIQUE(schedule_id, schedule_revision, planned_fire_at_utc, origin))");
        jdbc.execute("CREATE TABLE flow_schedule_outbox (event_id VARCHAR(36) PRIMARY KEY, trigger_id VARCHAR(36), "
                + "event_type VARCHAR(48), payload_json TEXT, delivery_state VARCHAR(16), attempts INT, "
                + "created_at TIMESTAMP(3), UNIQUE(trigger_id))");
        String schedule = UUID.randomUUID().toString();
        Instant due = Instant.now().minusSeconds(1);
        jdbc.update("INSERT INTO flow_schedule_definition VALUES (?,'admin',?, ?,1,1,'0 0 * * * *'," 
                        + "'Etc/UTC','FIRE_ONCE',true,?,1,?)", schedule, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                Timestamp.from(due), Timestamp.from(due));
        var manager = new DataSourceTransactionManager(source);
        ScheduleScanner a = new ScheduleScanner(jdbc, manager, new ObjectMapper());
        ScheduleScanner b = new ScheduleScanner(jdbc, manager, new ObjectMapper());
        CountDownLatch go = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> { go.await(); return a.scanOnce(); });
            var second = threads.submit(() -> { go.await(); return b.scanOnce(); });
            go.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_schedule_trigger", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flow_schedule_outbox", Integer.class));
        assertEquals("PENDING", jdbc.queryForObject("SELECT delivery_state FROM flow_schedule_outbox", String.class));
        assertEquals(2L, jdbc.queryForObject("SELECT version FROM flow_schedule_definition", Long.class));
        assertTrue(jdbc.queryForObject("SELECT next_fire_at_utc FROM flow_schedule_definition",
                Timestamp.class).toInstant().isAfter(due));
    }
}
