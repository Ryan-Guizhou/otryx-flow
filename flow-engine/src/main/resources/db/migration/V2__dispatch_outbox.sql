CREATE TABLE flow_engine_dispatch_outbox (
    run_id VARCHAR(36) NOT NULL PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00.000',
    last_error VARCHAR(120) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT fk_engine_dispatch_outbox_run FOREIGN KEY (run_id) REFERENCES flow_workflow_run(id),
    CONSTRAINT uq_engine_dispatch_outbox_event UNIQUE (event_id),
    INDEX idx_engine_dispatch_due (delivery_state, next_attempt_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve Engine runs accepted by previous deployments: idempotent EngineRun -> dispatch relay upgrade.
INSERT INTO flow_engine_dispatch_outbox (run_id, event_id, delivery_state, attempts, created_at)
SELECT id, event_id, 'PENDING', 0, created_at FROM flow_workflow_run
WHERE status = 'WAITING_DISPATCH';
