ALTER TABLE flow_user
    ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until DATETIME(3) NULL,
    ADD COLUMN credential_version INT NOT NULL DEFAULT 0,
    ADD COLUMN password_changed_at DATETIME(3) NULL;
