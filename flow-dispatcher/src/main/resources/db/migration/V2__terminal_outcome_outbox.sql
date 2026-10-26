-- Dispatcher terminal task state and its outcome Outbox are always committed atomically.
CREATE TABLE flow_dispatch_outcome_outbox (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    engine_run_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    lease_epoch BIGINT NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    delivery_state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36) NULL,
    lease_until DATETIME(3) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00.000',
    last_error VARCHAR(120) NULL,
    delivered_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    CONSTRAINT uq_dispatch_terminal_run UNIQUE (engine_run_id),
    CONSTRAINT fk_dispatch_terminal_task FOREIGN KEY (task_id) REFERENCES flow_dispatch_task(id),
    INDEX idx_dispatch_terminal_due (delivery_state, next_attempt_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill terminal tasks from older deployments, preserving the one-terminal-event invariant.
-- Use a deterministic UUID-shaped event ID only for legacy terminal records, never for live writes.
INSERT INTO flow_dispatch_outcome_outbox
    (event_id, engine_run_id, task_id, project_id, lease_epoch, outcome, delivery_state, attempts, created_at)
SELECT UUID(), t.engine_run_id, t.id, t.project_id, t.lease_epoch, t.status, 'PENDING', 0, t.updated_at
FROM flow_dispatch_task t WHERE t.status IN ('SUCCEEDED', 'FAILED');
