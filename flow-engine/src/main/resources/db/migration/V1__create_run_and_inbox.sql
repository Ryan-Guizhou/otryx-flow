CREATE TABLE flow_engine_inbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    payload_fingerprint CHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_engine_inbox_run UNIQUE (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_workflow_run (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    schedule_id VARCHAR(36) NOT NULL,
    schedule_revision INT NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    job_revision INT NOT NULL,
    planned_fire_at_utc DATETIME(3) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'WAITING_DISPATCH',
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_engine_run_event UNIQUE (event_id),
    CONSTRAINT uq_engine_logical_fire UNIQUE (schedule_id, schedule_revision, planned_fire_at_utc, origin),
    CONSTRAINT fk_engine_run_inbox FOREIGN KEY (event_id) REFERENCES flow_engine_inbox(event_id),
    INDEX idx_engine_project_recent (owner_username, project_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
