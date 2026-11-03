package com.peachsoft.otryx.flow.dispatcher;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按项目保留期删除已经终态且结果已投递的 Dispatcher 任务。
 *
 * <p>READY 与 LEASED 不会被删除。未投递的 Outcome Outbox 会挡住对应任务。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@Component
public class TerminalHistoryRetention {
    private final JdbcTemplate jdbc;

    public TerminalHistoryRetention(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 每小时最多删除 100 条终态任务；积压留到下一次。 */
    @Scheduled(fixedDelay = 3600000L, initialDelay = 3600000L)
    public int purgeOnce() {
        return AdmissionGate.purgeTerminal(jdbc, Instant.now());
    }
}
