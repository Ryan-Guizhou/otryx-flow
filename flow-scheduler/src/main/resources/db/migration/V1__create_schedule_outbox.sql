CREATE TABLE flow_schedule_definition (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(64) NOT NULL,
    create_request_key VARCHAR(80) NOT NULL,
    create_fingerprint CHAR(64) NOT NULL,
    job_id VARCHAR(36) NOT NULL,
    job_revision INT NOT NULL,
    schedule_revision INT NOT NULL DEFAULT 1,
    cron_expr VARCHAR(120) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    misfire_policy VARCHAR(16) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    next_fire_at_utc DATETIME(3) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_schedule_create_key UNIQUE (owner_username, create_request_key),
    INDEX idx_schedule_due (enabled, next_fire_at_utc, id),
    INDEX idx_schedule_owner (owner_username, project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_schedule_trigger (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    schedule_id VARCHAR(36) NOT NULL,
    schedule_revision INT NOT NULL,
    planned_fire_at_utc DATETIME(3) NOT NULL,
    origin VARCHAR(16) NOT NULL DEFAULT 'CRON',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_ENGINE',
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_trigger_schedule FOREIGN KEY (schedule_id) REFERENCES flow_schedule_definition(id),
    CONSTRAINT uq_schedule_fire UNIQUE (schedule_id, schedule_revision, planned_fire_at_utc, origin),
    INDEX idx_trigger_schedule (schedule_id, planned_fire_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE flow_schedule_outbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    trigger_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload_json TEXT NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    delivered_at DATETIME(3) NULL,
    CONSTRAINT uq_schedule_outbox_trigger UNIQUE (trigger_id),
    CONSTRAINT fk_outbox_trigger FOREIGN KEY (trigger_id) REFERENCES flow_schedule_trigger(id),
    INDEX idx_schedule_pending (delivery_state, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
