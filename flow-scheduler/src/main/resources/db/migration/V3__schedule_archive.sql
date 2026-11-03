ALTER TABLE flow_schedule_definition
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
