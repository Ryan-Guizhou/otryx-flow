CREATE TABLE flow_dispatch_log (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    task_id VARCHAR(36) NOT NULL,
    engine_run_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    level VARCHAR(8) NOT NULL,
    message VARCHAR(2048) NOT NULL,
    fields_json VARCHAR(4096) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_dispatch_log_sequence UNIQUE (task_id, lease_epoch, sequence_no),
    CONSTRAINT fk_dispatch_log_task FOREIGN KEY (task_id) REFERENCES flow_dispatch_task(id),
    INDEX idx_dispatch_log_query (owner_username, project_id, engine_run_id, created_at, id),
    INDEX idx_dispatch_log_retention (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
