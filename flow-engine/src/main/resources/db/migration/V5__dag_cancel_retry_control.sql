-- FLOW-017: preserve immutable NodeRun history and add optimistic Run control.
ALTER TABLE flow_dag_run
    ADD COLUMN run_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN cancel_requested_at DATETIME(3) NULL,
    ADD COLUMN cancelled_at DATETIME(3) NULL;

-- One user-visible mutation per stable request key, with immutable payload SHA-256 for replay.
CREATE TABLE flow_dag_control_audit (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    dag_run_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    action VARCHAR(16) NOT NULL,
    node_id VARCHAR(128) NULL,
    node_run_id VARCHAR(36) NULL,
    old_version BIGINT NOT NULL,
    new_version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_dag_control_run FOREIGN KEY (dag_run_id) REFERENCES flow_dag_run(id),
    CONSTRAINT uq_dag_control_request UNIQUE (owner_username, request_key),
    INDEX idx_dag_control_history (dag_run_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Request persisted before network I/O, and retried after Engine restart.
CREATE TABLE flow_dag_cancel_outbox (
    node_run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00.000',
    last_error VARCHAR(120) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_dag_cancel_node FOREIGN KEY (node_run_id) REFERENCES flow_dag_node_run(id),
    INDEX idx_dag_cancel_due (delivery_state, next_attempt_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

