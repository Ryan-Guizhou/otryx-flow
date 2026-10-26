package com.peachsoft.otryx.flow.metadata.job;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peachsoft.otryx.flow.contracts.v1.JobCreateDraftCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobDraftUpdateCommand;
import com.peachsoft.otryx.flow.contracts.v1.JobPublishCommand;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Job draft, publish, retry, fencing and authorization unit tests. */
class JobRpcServiceTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    private JobRpcService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:jobs-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE flow_project (id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(64))");
        jdbc.update("INSERT INTO flow_project (id, owner_username) VALUES (?,?)", PROJECT, "admin");
        jdbc.execute("CREATE TABLE flow_job_definition (id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36), "
                + "owner_username VARCHAR(64), create_request_key VARCHAR(80), create_fingerprint CHAR(64), "
                + "name VARCHAR(100), handler_key VARCHAR(128), parameters_json TEXT, timeout_seconds INT, "
                + "max_attempts INT, draft_version BIGINT, published_revision INT, "
                + "last_published_draft_version BIGINT, created_at TIMESTAMP, updated_at TIMESTAMP, "
                + "UNIQUE(owner_username,create_request_key))");
        jdbc.execute("CREATE TABLE flow_job_revision (job_id VARCHAR(36), revision INT, "
                + "publish_request_key VARCHAR(80), name VARCHAR(100), handler_key VARCHAR(128), "
                + "parameters_json TEXT, timeout_seconds INT, max_attempts INT, "
                + "source_draft_version BIGINT, published_at TIMESTAMP, "
                + "PRIMARY KEY(job_id,revision), UNIQUE(job_id,publish_request_key))");
        service = new JobRpcService(jdbc, new DataSourceTransactionManager(source), new ObjectMapper());
    }

    @Test
    void repeatedCreateIsIdempotentAndChangedBodyIsConflict() {
        String key = UUID.randomUUID().toString();
        var first = service.createDraft(create(key, "Refresh search index"));
        var replay = service.createDraft(create(key, "Refresh search index"));
        assertEquals("OK", first.code());
        assertEquals(first.job().id(), replay.job().id());
        assertTrue(replay.duplicate());
        assertEquals("CONFLICT", service.createDraft(create(key, "Different task")).code());
        assertEquals(1, service.list("admin", PROJECT).jobs().length);
    }

    @Test
    void publishIsImmutableAndDraftCasPreventsLostUpdates() {
        String job = service.createDraft(create(UUID.randomUUID().toString(), "Task one")).job().id();
        String publishKey = UUID.randomUUID().toString();
        var first = service.publish(new JobPublishCommand("admin", PROJECT, job, 1, publishKey));
        assertEquals("OK", first.code());
        assertEquals(1, first.job().publishedRevision());
        assertTrue(service.publish(new JobPublishCommand("admin", PROJECT, job, 1, publishKey)).duplicate());
        assertEquals("CONFLICT", service.publish(new JobPublishCommand("admin", PROJECT, job, 1,
                UUID.randomUUID().toString())).code());
        var old = service.revision("admin", PROJECT, job, 1);
        assertEquals("Task one", old.name());
        assertEquals("OK", service.saveDraft(new JobDraftUpdateCommand("admin", PROJECT, job, 1,
                "Task two", "safe.echo", "{\"value\":2}", 15, 2)).code());
        assertEquals("CONFLICT", service.saveDraft(new JobDraftUpdateCommand("admin", PROJECT, job, 1,
                "Unexpected", "safe.echo", "{}", 30, 1)).code());
        assertEquals("OK", service.publish(new JobPublishCommand("admin", PROJECT, job, 2,
                UUID.randomUUID().toString())).code());
        assertEquals(2, service.list("admin", PROJECT).jobs()[0].publishedRevision());
        assertEquals("Task one", service.revision("admin", PROJECT, job, 1).name());
        assertEquals("Task two", service.revision("admin", PROJECT, job, 2).name());
    }

    @Test
    void wrongOwnerAndProjectHaveNoAccess() {
        String job = service.createDraft(create(UUID.randomUUID().toString(), "Shared? No")).job().id();
        assertEquals(0, service.list("other", PROJECT).jobs().length);
        assertNull(service.revision("other", PROJECT, job, 1));
        assertEquals("NOT_FOUND", service.saveDraft(new JobDraftUpdateCommand("other", PROJECT, job, 1,
                "Hijack", "safe.echo", "{}", 10, 1)).code());
    }

    @Test
    void rejectMalformedParametersAndUnsafeHandlerKeys() {
        assertThrows(IllegalArgumentException.class, () -> service.createDraft(
                new JobCreateDraftCommand("admin", PROJECT, UUID.randomUUID().toString(),
                        "Invalid JSON", "safe.echo", "[1,2]", 10, 1)));
        assertThrows(IllegalArgumentException.class, () -> service.createDraft(
                new JobCreateDraftCommand("admin", PROJECT, UUID.randomUUID().toString(),
                        "Unsafe handler", "java.lang.Runtime.exec", "{}", 10, 1)));
    }

    private static JobCreateDraftCommand create(String key, String name) {
        return new JobCreateDraftCommand("admin", PROJECT, key, name, "safe.echo", "{}", 10, 2);
    }
}
