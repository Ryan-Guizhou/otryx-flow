CREATE TABLE flow_audit_event (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    actor VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    project_id VARCHAR(36) NULL,
    outcome VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    INDEX idx_flow_audit_created (created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
