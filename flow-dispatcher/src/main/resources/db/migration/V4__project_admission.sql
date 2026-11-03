CREATE TABLE flow_project_admission (
    project_id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_username VARCHAR(64) NOT NULL,
    max_inflight INT NOT NULL,
    max_queued INT NOT NULL,
    concurrency_policy VARCHAR(16) NOT NULL,
    retention_days INT NOT NULL,
    updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_worker_drain (
    worker_id VARCHAR(64) NOT NULL PRIMARY KEY,
    drain_until DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
