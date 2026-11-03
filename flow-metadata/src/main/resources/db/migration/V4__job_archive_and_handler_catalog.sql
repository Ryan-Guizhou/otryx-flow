ALTER TABLE flow_job_definition
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE flow_handler_catalog (
    handler_key VARCHAR(128) NOT NULL PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    parameter_schema TEXT NOT NULL,
    supports_cancel BOOLEAN NOT NULL,
    enabled BOOLEAN NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO flow_handler_catalog (handler_key, display_name, parameter_schema, supports_cancel, enabled) VALUES
    ('safe.echo', 'Echo', '{}', TRUE, TRUE),
    ('safe.fail', 'Intentional failure', '{}', TRUE, TRUE),
    ('safe.summarize', 'Sum numeric values', '{"type":"object","properties":{"values":{"type":"array"}}}', TRUE, TRUE);
