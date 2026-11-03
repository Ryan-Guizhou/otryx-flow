ALTER TABLE flow_dispatch_task
    MODIFY schedule_id VARCHAR(36) NULL,
    ADD COLUMN target_type VARCHAR(16) NOT NULL DEFAULT 'LEGACY',
    ADD INDEX idx_dispatch_project_status (project_id, status, target_type, job_id);

CREATE TABLE flow_worker_registration (
    worker_id VARCHAR(64) NOT NULL PRIMARY KEY,
    heartbeat_at DATETIME(3) NOT NULL,
    capacity INT NOT NULL,
    occupied INT NOT NULL,
    handlers_csv VARCHAR(1024) NOT NULL,
    updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
