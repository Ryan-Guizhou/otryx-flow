package com.peachsoft.otryx.flow.metadata.project;

import static org.junit.jupiter.api.Assertions.*;

import com.peachsoft.otryx.flow.contracts.v1.ProjectCreateCommand;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies project ownership and idempotent retry without a remote registry. */
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
        ProjectCreateCommand request = new ProjectCreateCommand("admin", "00000000-0000-4000-8000-000000000001", "First");
        var first = service.create(request);
        var retry = service.create(request);
        assertEquals(first.project().id(), retry.project().id());
        assertTrue(retry.duplicate());
        assertFalse(retry.conflict());
        assertEquals(1, service.listByOwner("admin").projects().length);
        assertEquals(0, service.listByOwner("other").projects().length);
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
