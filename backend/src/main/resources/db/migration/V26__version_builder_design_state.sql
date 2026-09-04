ALTER TABLE builder_workflow_versions
    ADD COLUMN design_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb;
