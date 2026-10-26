CREATE TABLE flow_project (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    owner_username VARCHAR(64) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_flow_project_idempotency UNIQUE (owner_username, request_key),
    INDEX idx_flow_project_owner_created (owner_username, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
