-- Engine owns durable logical Run terminal state. Dispatcher has no direct write access.
CREATE TABLE flow_engine_outcome_inbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    engine_run_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    accepted_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_engine_outcome_run UNIQUE (engine_run_id),
    CONSTRAINT fk_engine_outcome_run FOREIGN KEY (engine_run_id) REFERENCES flow_workflow_run(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
