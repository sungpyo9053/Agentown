ALTER TABLE builder_runs
    ADD COLUMN IF NOT EXISTS run_mode VARCHAR(30) NOT NULL DEFAULT 'SIMULATION',
    ADD COLUMN IF NOT EXISTS destination_json JSONB,
    ADD COLUMN IF NOT EXISTS external_write_request_id UUID REFERENCES notion_page_write_requests(id),
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS failure_code VARCHAR(80),
    ADD COLUMN IF NOT EXISTS failure_message VARCHAR(500),
    ADD COLUMN IF NOT EXISTS input_tokens BIGINT,
    ADD COLUMN IF NOT EXISTS output_tokens BIGINT;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_builder_run_mode') THEN
        ALTER TABLE builder_runs
            ADD CONSTRAINT ck_builder_run_mode CHECK (run_mode IN ('SIMULATION', 'PRODUCTION'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_builder_run_attempt_count') THEN
        ALTER TABLE builder_runs
            ADD CONSTRAINT ck_builder_run_attempt_count CHECK (attempt_count BETWEEN 0 AND 3);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_builder_production_runs
    ON builder_runs(workspace_id, run_mode, created_at DESC);
