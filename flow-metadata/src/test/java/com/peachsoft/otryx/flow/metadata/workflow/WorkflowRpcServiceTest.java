package com.peachsoft.otryx.flow.metadata.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowPublishCommand;
import com.peachsoft.otryx.flow.contracts.v1.WorkflowSaveDraftCommand;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 真实 JDBC 事务模拟：所有者隔离、幂等请求、不可变发布与 CAS。 */
class WorkflowRpcServiceTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private static final String GRAPH = """
            {"schemaVersion":1,"nodes":[{"id":"A","type":"TASK","handlerKey":"safe.echo",
             "parameters":{},"timeoutSeconds":30,"maxAttempts":2}],"edges":[]}
            """;
    private WorkflowRpcService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:workflows-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_project (id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(64))");
        jdbc.update("INSERT INTO flow_project (id, owner_username) VALUES (?,?)", PROJECT, "admin");
        jdbc.execute("CREATE TABLE flow_workflow_definition ("
                + "id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36), owner_username VARCHAR(64), "
                + "create_request_key VARCHAR(80), create_fingerprint CHAR(64), name VARCHAR(100), "
                + "graph_json TEXT, draft_version BIGINT, published_revision INT, "
                + "last_published_draft_version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP, "
                + "UNIQUE(owner_username, create_request_key))");
        jdbc.execute("CREATE TABLE flow_workflow_revision ("
                + "workflow_id VARCHAR(36), revision INT, publish_request_key VARCHAR(80), "
                + "name VARCHAR(100), graph_json TEXT, graph_sha256 CHAR(64), "
                + "source_draft_version BIGINT, published_at TIMESTAMP, "
                + "PRIMARY KEY(workflow_id,revision), UNIQUE(workflow_id,publish_request_key))");
        service = new WorkflowRpcService(jdbc, new DataSourceTransactionManager(source), new ObjectMapper());
    }

    @Test
    void sameKeyAndSameGraphAreIdempotentButChangedBodyConflicts() {
        String key = UUID.randomUUID().toString();
        var first = service.createDraft(create(key, "First", GRAPH));
        var replay = service.createDraft(create(key, "First", GRAPH));
        assertEquals("OK", first.code());
        assertTrue(replay.duplicate());
        assertEquals(first.workflow().id(), replay.workflow().id());
        assertEquals("CONFLICT", service.createDraft(create(key, "Changed", GRAPH)).code());
        assertEquals(1, service.list("admin", PROJECT).workflows().length);
    }

    @Test
    void publicationIsImmutableAndStaleCasCannotOverwrite() {
        String id = service.createDraft(create(UUID.randomUUID().toString(), "R1", GRAPH)).workflow().id();
        String publishKey = UUID.randomUUID().toString();
        var first = service.publish(new WorkflowPublishCommand("admin", PROJECT, id, 1, publishKey));
        assertEquals("OK", first.code());
        assertEquals(1, first.workflow().publishedRevision());
        assertTrue(service.publish(new WorkflowPublishCommand("admin", PROJECT, id, 1, publishKey)).duplicate());
        assertEquals("CONFLICT", service.publish(new WorkflowPublishCommand("admin", PROJECT, id,
                2, publishKey)).code());
        assertEquals("CONFLICT", service.publish(new WorkflowPublishCommand("admin", PROJECT, id,
                1, UUID.randomUUID().toString())).code());

        String changed = GRAPH.replace("safe.echo", "safe.fail");
        assertEquals("OK", service.saveDraft(new WorkflowSaveDraftCommand(
                "admin", PROJECT, id, 1, "R2", changed)).code());
        assertEquals("CONFLICT", service.saveDraft(new WorkflowSaveDraftCommand(
                "admin", PROJECT, id, 1, "Stale", GRAPH)).code());
        assertEquals("OK", service.publish(new WorkflowPublishCommand("admin", PROJECT, id,
                2, UUID.randomUUID().toString())).code());
        var r1 = service.revision("admin", PROJECT, id, 1);
        var r2 = service.revision("admin", PROJECT, id, 2);
        assertEquals("R1", r1.name());
        assertEquals("R2", r2.name());
        assertTrue(r1.graphJson().contains("safe.echo"));
        assertTrue(r2.graphJson().contains("safe.fail"));
        assertFalse(r1.graphSha256().equals(r2.graphSha256()));
        assertEquals(64, r1.graphSha256().length());
        assertEquals(2, r2.sourceDraftVersion());
    }

    @Test
    void projectOwnershipIsCheckedBeforeEveryMutationAndRead() {
        String id = service.createDraft(create(UUID.randomUUID().toString(), "Owned", GRAPH)).workflow().id();
        assertEquals(0, service.list("guest", PROJECT).workflows().length);
        assertNull(service.revision("guest", PROJECT, id, 1));
        assertEquals("NOT_FOUND", service.saveDraft(new WorkflowSaveDraftCommand(
                "guest", PROJECT, id, 1, "Hijack", GRAPH)).code());
        assertEquals("NOT_FOUND", service.publish(new WorkflowPublishCommand(
                "guest", PROJECT, id, 1, UUID.randomUUID().toString())).code());
        assertEquals("NOT_FOUND", service.createDraft(new WorkflowCreateDraftCommand(
                "guest", PROJECT, UUID.randomUUID().toString(), "Hijack", GRAPH)).code());
        assertNotNull(service.list("admin", PROJECT).workflows()[0]);
    }

    @Test
    void invalidGraphCannotBeSavedOrPublished() {
        assertEquals("INVALID", service.createDraft(create(UUID.randomUUID().toString(), "Bad",
                GRAPH.replace("safe.echo", "java.lang.Runtime.exec"))).code());
        String id = service.createDraft(create(UUID.randomUUID().toString(), "Valid", GRAPH)).workflow().id();
        assertEquals("INVALID", service.saveDraft(new WorkflowSaveDraftCommand(
                "admin", PROJECT, id, 1, "Bad", "[]")).code());
        assertEquals(1, service.list("admin", PROJECT).workflows()[0].draftVersion());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM flow_workflow_revision", Integer.class));
    }

    private static WorkflowCreateDraftCommand create(String key, String name, String graph) {
        return new WorkflowCreateDraftCommand("admin", PROJECT, key, name, graph);
    }
}

