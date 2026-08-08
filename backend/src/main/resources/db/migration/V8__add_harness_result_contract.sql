ALTER TABLE harnesses
    ADD COLUMN result_format VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN result_step_key VARCHAR(100);

ALTER TABLE harnesses
    ADD CONSTRAINT ck_harness_result_format
        CHECK (result_format IN ('AUTO', 'TEXT', 'MARKDOWN', 'HTML', 'JSON', 'CSV', 'EXTERNAL'));
