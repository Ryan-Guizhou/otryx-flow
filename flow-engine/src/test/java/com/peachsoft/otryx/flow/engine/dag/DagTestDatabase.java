package com.peachsoft.otryx.flow.engine.dag;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** 测试专用单独数据库与完整最小表结构，不访问其他服务的数据源。 */
final class DagTestDatabase {
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager manager;
    final ObjectMapper mapper = new ObjectMapper();
    final DagRunCoordinator coordinator;
    static final String PROJECT = "00000000-0000-4000-8000-000000000001";
    static final String WORKFLOW = "00000000-0000-4000-8000-000000000002";
    static final String SINGLE = graph(task("A"), "");

    DagTestDatabase() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:dag-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        coordinator = new DagRunCoordinator(jdbc, mapper);
        jdbc.execute("CREATE TABLE flow_dag_run (id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(64), "
                + "project_id VARCHAR(36), workflow_id VARCHAR(36), workflow_revision INT, "
                + "graph_sha256 CHAR(64), graph_json TEXT, input_json TEXT, request_key VARCHAR(80), "
                + "request_fingerprint CHAR(64), status VARCHAR(24), run_version BIGINT DEFAULT 1, "
                + "cancel_requested_at TIMESTAMP(3), cancelled_at TIMESTAMP(3), created_at TIMESTAMP(3), "
                + "updated_at TIMESTAMP(3), UNIQUE(owner_username,request_key))");
        jdbc.execute("CREATE TABLE flow_dag_node_run (id VARCHAR(36) PRIMARY KEY, dag_run_id VARCHAR(36), "
                + "node_id VARCHAR(128), logical_attempt INT, node_type VARCHAR(16), status VARCHAR(24), "
                + "condition_decision BOOLEAN, handler_key VARCHAR(128), parameters_json TEXT, "
                + "timeout_seconds INT, max_attempts INT, created_at TIMESTAMP(3), updated_at TIMESTAMP(3), "
                + "UNIQUE(dag_run_id,node_id,logical_attempt))");
        jdbc.execute("CREATE TABLE flow_dag_dispatch_outbox (node_run_id VARCHAR(36) PRIMARY KEY, "
                + "event_id VARCHAR(36) UNIQUE, delivery_state VARCHAR(16), claim_token VARCHAR(36), "
                + "lease_until TIMESTAMP(3), attempts INT, next_attempt_at TIMESTAMP(3) "
                + "DEFAULT TIMESTAMP '1970-01-01 00:00:00', last_error VARCHAR(120), "
                + "delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_dag_outcome_inbox (event_id VARCHAR(36) PRIMARY KEY, "
                + "node_run_id VARCHAR(36) UNIQUE, task_id VARCHAR(36), project_id VARCHAR(36), "
                + "lease_epoch BIGINT, outcome VARCHAR(16), payload_fingerprint CHAR(64), accepted_at TIMESTAMP(3))");
        jdbc.execute("CREATE TABLE flow_dag_control_audit (id VARCHAR(36) PRIMARY KEY, "
                + "dag_run_id VARCHAR(36), owner_username VARCHAR(64), request_key VARCHAR(80), "
                + "request_fingerprint CHAR(64), action VARCHAR(16), node_id VARCHAR(128), "
                + "node_run_id VARCHAR(36), old_version BIGINT, new_version BIGINT, "
                + "created_at TIMESTAMP(3), UNIQUE(owner_username,request_key))");
        jdbc.execute("CREATE TABLE flow_dag_cancel_outbox (node_run_id VARCHAR(36) PRIMARY KEY, "
                + "delivery_state VARCHAR(16), claim_token VARCHAR(36), lease_until TIMESTAMP(3), "
                + "attempts INT, next_attempt_at TIMESTAMP(3) DEFAULT TIMESTAMP '1970-01-01 00:00:00', "
                + "last_error VARCHAR(120), delivered_at TIMESTAMP(3), created_at TIMESTAMP(3))");
    }

    static String task(String name) {
        return "{\"id\":\"" + name + "\",\"type\":\"TASK\",\"handlerKey\":\"safe.echo\","
                + "\"parameters\":{},\"timeoutSeconds\":30,\"maxAttempts\":2}";
    }

    static String edge(String id, String source, String target) {
        return "{\"id\":\"" + id + "\",\"source\":\"" + source
                + "\",\"target\":\"" + target + "\",\"branch\":\"ALWAYS\"}";
    }

    static String graph(String nodes, String edges) {
        return "{\"schemaVersion\":1,\"nodes\":[" + nodes + "],\"edges\":[" + edges + "]}";
    }

    static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
