CREATE TABLE flow_job_definition (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    create_request_key VARCHAR(80) NOT NULL,
    create_fingerprint CHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    handler_key VARCHAR(128) NOT NULL,
    parameters_json TEXT NOT NULL,
    timeout_seconds INT NOT NULL,
    max_attempts INT NOT NULL,
    draft_version BIGINT NOT NULL,
    published_revision INT NOT NULL DEFAULT 0,
    last_published_draft_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_flow_job_project FOREIGN KEY (project_id) REFERENCES flow_project(id),
    CONSTRAINT uq_flow_job_create_request UNIQUE (owner_username, create_request_key),
    INDEX idx_flow_job_project_updated (project_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_job_revision (
    job_id VARCHAR(36) NOT NULL,
    revision INT NOT NULL,
    publish_request_key VARCHAR(80) NOT NULL,
    name VARCHAR(100) NOT NULL,
    handler_key VARCHAR(128) NOT NULL,
    parameters_json TEXT NOT NULL,
    timeout_seconds INT NOT NULL,
    max_attempts INT NOT NULL,
    source_draft_version BIGINT NOT NULL,
    published_at DATETIME(3) NOT NULL,
    PRIMARY KEY (job_id, revision),
    CONSTRAINT uq_flow_job_publish_request UNIQUE (job_id, publish_request_key),
    CONSTRAINT fk_flow_revision_job FOREIGN KEY (job_id) REFERENCES flow_job_definition(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
