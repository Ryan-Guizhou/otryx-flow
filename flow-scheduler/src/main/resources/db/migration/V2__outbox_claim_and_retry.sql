ALTER TABLE flow_schedule_outbox
    ADD COLUMN claim_token VARCHAR(36) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD COLUMN next_attempt_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00.000',
    ADD COLUMN last_error VARCHAR(120) NULL;

CREATE INDEX idx_schedule_outbox_retry
    ON flow_schedule_outbox (delivery_state, next_attempt_at, created_at);
