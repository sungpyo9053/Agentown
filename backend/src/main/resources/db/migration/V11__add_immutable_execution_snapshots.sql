ALTER TABLE executions
    ADD COLUMN execution_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN credential_bindings_json JSONB NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN executions.execution_snapshot_json IS
    'Immutable published harness definition captured when the execution is created; excludes credentials and secrets.';

COMMENT ON COLUMN executions.credential_bindings_json IS
    'Internal owner-scoped credential references keyed by snapshot agent key; never exposed through execution APIs.';
