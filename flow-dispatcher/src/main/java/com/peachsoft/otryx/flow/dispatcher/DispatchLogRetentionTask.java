package com.peachsoft.otryx.flow.dispatcher;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes expired log rows in bounded batches; execution state and Attempts are retained. */
@Component
public class DispatchLogRetentionTask {
    private static final int DELETE_BATCH = 10000;
    private final JdbcTemplate jdbc;

    public DispatchLogRetentionTask(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One bounded cleanup batch per hour; repeat on the next run if backlog remains. */
    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public int deleteExpiredOnce() {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(14L * 24L * 3600L));
        return jdbc.update("DELETE FROM flow_dispatch_log WHERE created_at < ? ORDER BY created_at LIMIT ?",
                cutoff, DELETE_BATCH);
    }
}
