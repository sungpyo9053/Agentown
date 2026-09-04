ALTER TABLE builder_runs
    ADD COLUMN run_mode VARCHAR(30) NOT NULL DEFAULT 'SIMULATION',
    ADD COLUMN destination_json JSONB,
    ADD COLUMN external_write_request_id UUID REFERENCES notion_page_write_requests(id),
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failure_code VARCHAR(80),
    ADD COLUMN failure_message VARCHAR(500),
    ADD COLUMN input_tokens BIGINT,
    ADD COLUMN output_tokens BIGINT;

ALTER TABLE builder_runs
    ADD CONSTRAINT ck_builder_run_mode CHECK (run_mode IN ('SIMULATION', 'PRODUCTION')),
    ADD CONSTRAINT ck_builder_run_attempt_count CHECK (attempt_count BETWEEN 0 AND 3);

CREATE INDEX idx_builder_production_runs
    ON builder_runs(workspace_id, run_mode, created_at DESC);

ALTER TABLE notion_page_write_requests
    DROP CONSTRAINT ck_notion_write_status,
    ADD CONSTRAINT ck_notion_write_status CHECK (status IN ('PREVIEWED','APPROVED','PUBLISHING','SUCCEEDED','FAILED','AMBIGUOUS'));
