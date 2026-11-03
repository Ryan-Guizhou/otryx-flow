package com.peachsoft.otryx.flow.api.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把关键管理操作写入身份库的追加审计表。
 *
 * <p>不保存参数正文、口令或租约令牌。写入失败时抛出异常，调用方不能把未审计的变更伪装成成功。</p>
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 11:20
 */
@Component
public class FlowAuditRecorder {
    private final JdbcTemplate jdbc;

    public FlowAuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 追加一条不可变审计。projectId 可空。 */
    public void record(String actor, String action, String projectId, String outcome) {
        jdbc.update("INSERT INTO flow_audit_event (id, actor, action, project_id, outcome, created_at) "
                        + "VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actor, action, projectId, outcome,
                Timestamp.from(Instant.now()));
    }

    /** 最近 100 条审计，按时间倒序。 */
    public List<AuditEvent> latest() {
        return jdbc.query("SELECT actor, action, project_id, outcome, created_at FROM flow_audit_event "
                        + "ORDER BY created_at DESC, id DESC LIMIT 100",
                (rs, index) -> new AuditEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant().toString()));
    }

    /**
     * 审计查询行。
     *
     * @Author Ryan-Guizhou
     * @Version 0.1.0-SNAPSHOT
     * @CreateTime 2026/11/03 11:20
     */
    public record AuditEvent(String actor, String action, String projectId, String outcome, String createdAt) { }
}
