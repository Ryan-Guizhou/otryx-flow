ALTER TABLE flow_workflow_run
    MODIFY schedule_id VARCHAR(36) NULL,
    MODIFY schedule_revision INT NULL,
    MODIFY planned_fire_at_utc DATETIME(3) NULL,
    ADD COLUMN control_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN run_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN started_at DATETIME(3) NULL,
    ADD COLUMN finished_at DATETIME(3) NULL,
    ADD COLUMN failure_code VARCHAR(64) NULL,
    ADD COLUMN retry_of_run_id VARCHAR(36) NULL,
    ADD COLUMN parameter_override TEXT NULL,
    ADD INDEX idx_engine_job_recent (job_id, created_at),
    ADD INDEX idx_engine_status_recent (status, created_at);

CREATE TABLE flow_job_run_request (
    owner_username VARCHAR(64) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_username, request_key),
    CONSTRAINT fk_job_run_request_run FOREIGN KEY (run_id) REFERENCES flow_workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_engine_run_cancel_outbox (
    run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL,
    last_error VARCHAR(120) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_engine_cancel_outbox_run FOREIGN KEY (run_id) REFERENCES flow_workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
