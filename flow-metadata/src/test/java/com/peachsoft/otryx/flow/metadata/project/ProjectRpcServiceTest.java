package com.peachsoft.otryx.flow.metadata.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peachsoft.otryx.flow.contracts.v1.ProjectCreateCommand;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 校验项目归属和幂等重试，不连接远程注册中心。
 *
 * @Author Ryan-Guizhou
 * @Version 0.1.0-SNAPSHOT
 * @CreateTime 2026/11/03 14:40
 */
class ProjectRpcServiceTest {
    private ProjectRpcService service;

    @BeforeEach
    void setup() {
        JdbcDataSource datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:metadata-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE TABLE flow_project (id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(64), "
                + "request_key VARCHAR(80), name VARCHAR(100), created_at TIMESTAMP, "
                + "UNIQUE(owner_username, request_key))");
        service = new ProjectRpcService(jdbc);
    }

    @Test
    void retryWithSameKeyReturnsSameProjectWithoutDuplicateWrite() {
        ProjectCreateCommand request = new ProjectCreateCommand("admin",
                "00000000-0000-4000-8000-000000000001", "First");
        var first = service.create(request);
        var retry = service.create(request);
        assertEquals(first.project().id(), retry.project().id());
        assertTrue(retry.duplicate());
        assertFalse(retry.conflict());
        assertEquals(1, service.listByOwner("admin").projects().length);
        assertEquals(0, service.listByOwner("other").projects().length);
        assertTrue(service.hasAccess("admin", first.project().id()));
        assertFalse(service.hasAccess("other", first.project().id()));
    }

    @Test
    void otherOwnerCannotSeeProject() {
        var created = service.create(new ProjectCreateCommand("admin",
                "00000000-0000-4000-8000-000000000009", "Owned"));
        assertFalse(service.hasAccess("other", created.project().id()));
    }

    @Test
    void sameKeyWithChangedNameReportsConflict() {
        String key = "00000000-0000-4000-8000-000000000002";
        service.create(new ProjectCreateCommand("admin", key, "One"));
        var second = service.create(new ProjectCreateCommand("admin", key, "Changed"));
        assertTrue(second.conflict());
        assertEquals("One", second.project().name());
    }

    @Test
    void invalidOwnerOrNameRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.listByOwner("../other"));
        assertThrows(IllegalArgumentException.class, () -> service.create(
                new ProjectCreateCommand("admin", "00000000-0000-4000-8000-000000000003", " ")));
    }
}
