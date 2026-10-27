-- Dedicated M3 DAG model; never reinterpret existing M2 flow_workflow_run records.
CREATE TABLE flow_dag_run (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_username VARCHAR(64) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    workflow_id VARCHAR(36) NOT NULL,
    workflow_revision INT NOT NULL,
    graph_sha256 CHAR(64) NOT NULL,
    graph_json TEXT NOT NULL,
    input_json TEXT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'RUNNING',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_dag_request UNIQUE (owner_username, request_key),
    INDEX idx_dag_project_recent (owner_username, project_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_dag_node_run (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    dag_run_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(128) NOT NULL,
    logical_attempt INT NOT NULL DEFAULT 1,
    node_type VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'BLOCKED',
    condition_decision BOOLEAN NULL,
    handler_key VARCHAR(128) NULL,
    parameters_json TEXT NULL,
    timeout_seconds INT NULL,
    max_attempts INT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_dag_node_run FOREIGN KEY (dag_run_id) REFERENCES flow_dag_run(id),
    CONSTRAINT uq_dag_node_attempt UNIQUE (dag_run_id, node_id, logical_attempt),
    INDEX idx_dag_run_nodes (dag_run_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Each NodeRun ID is the stable M2 Dispatcher enqueue.runId; a DAG Run owns many NodeRuns.
CREATE TABLE flow_dag_dispatch_outbox (
    node_run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00.000',
    last_error VARCHAR(120) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_dag_dispatch_node FOREIGN KEY (node_run_id) REFERENCES flow_dag_node_run(id),
    CONSTRAINT uq_dag_dispatch_event UNIQUE (event_id),
    INDEX idx_dag_outbox_due (delivery_state, next_attempt_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_dag_outcome_inbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    node_run_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_dag_node_outcome UNIQUE (node_run_id),
    CONSTRAINT fk_dag_outcome_node FOREIGN KEY (node_run_id) REFERENCES flow_dag_node_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
