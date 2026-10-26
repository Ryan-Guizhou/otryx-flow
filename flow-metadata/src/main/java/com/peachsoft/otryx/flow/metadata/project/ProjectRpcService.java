package com.peachsoft.otryx.flow.metadata.project;

import com.peachsoft.otryx.flow.contracts.v1.ProjectCreateCommand;
import com.peachsoft.otryx.flow.contracts.v1.ProjectCreatedReply;
import com.peachsoft.otryx.flow.contracts.v1.ProjectListReply;
import com.peachsoft.otryx.flow.contracts.v1.ProjectRpc;
import com.peachsoft.otryx.flow.contracts.v1.ProjectView;
import com.peachsoft.otryx.spring.annotation.OtryxRpcService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 项目数据持久化 RPC 服务，不与 flow-api 共享 JDBC 访问。 */
@OtryxRpcService(interfaceClass = ProjectRpc.class, version = "1.0.0")
public class ProjectRpcService implements ProjectRpc {
    private static final int MAX_NAME = 100;
    private static final Pattern OWNER_PATTERN = Pattern.compile("[a-zA-Z0-9_.-]{1,64}");
    private static final Pattern KEY_PATTERN = Pattern.compile("[a-zA-Z0-9_-]{16,80}");
    private static final int MAX_ROWS = 100;
    private final JdbcTemplate jdbc;

    /** 使用元数据数据库创建 RPC 服务实例。 */
    public ProjectRpcService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ProjectListReply listByOwner(String ownerUsername) {
        String owner = validOwner(ownerUsername);
        var projects = jdbc.query("SELECT id, name, created_at FROM flow_project "
                + "WHERE owner_username = ? ORDER BY created_at DESC, id DESC LIMIT ?",
                (rs, index) -> view(rs), owner, MAX_ROWS);
        return new ProjectListReply(projects.toArray(ProjectView[]::new));
    }

    @Override
    public ProjectCreatedReply create(ProjectCreateCommand command) {
        if (command == null) { throw new IllegalArgumentException("Command is required"); }
        String owner = validOwner(command.ownerUsername());
        String key = validKey(command.requestKey());
        String name = validName(command.name());
        try {
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO flow_project(id, owner_username, request_key, name, created_at) "
                    + "VALUES (?, ?, ?, ?, ?)", id, owner, key, name,
                    java.sql.Timestamp.from(Instant.now()));
            return lookup(owner, key, name, false);
        } catch (DuplicateKeyException alreadyPresent) {
            return lookup(owner, key, name, true);
        }
    }

    private ProjectCreatedReply lookup(String owner, String key, String expectedName, boolean duplicate) {
        return jdbc.queryForObject("SELECT id, name, created_at FROM flow_project "
                + "WHERE owner_username = ? AND request_key = ?", (rs, index) -> {
                    ProjectView value = view(rs);
                    return new ProjectCreatedReply(value, duplicate, !value.name().equals(expectedName));
                }, owner, key);
    }

    private static ProjectView view(ResultSet rs) throws SQLException {
        return new ProjectView(rs.getString("id"), rs.getString("name"),
                rs.getTimestamp("created_at").toInstant().toString());
    }

    private static String validOwner(String owner) {
        if (owner == null || !OWNER_PATTERN.matcher(owner).matches()) {
            throw new IllegalArgumentException("Invalid principal");
        }
        return owner;
    }

    private static String validKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid idempotency key");
        }
        return key;
    }

    private static String validName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > MAX_NAME) {
            throw new IllegalArgumentException("Invalid project name");
        }
        return name.strip();
    }
}
