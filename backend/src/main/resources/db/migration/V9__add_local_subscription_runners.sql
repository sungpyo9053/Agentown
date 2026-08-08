CREATE TABLE local_runner_connections (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    device_name VARCHAR(100) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_local_runner_provider CHECK (provider IN ('CODEX', 'CLAUDE')),
    CONSTRAINT ck_local_runner_status CHECK (status IN ('PENDING', 'ACTIVE', 'OFFLINE', 'REVOKED'))
);
CREATE INDEX idx_local_runner_owner ON local_runner_connections(owner_id, created_at DESC);

ALTER TABLE executions ADD COLUMN execution_mode VARCHAR(20) NOT NULL DEFAULT 'CLOUD_API';
ALTER TABLE executions ADD COLUMN runner_connection_id UUID REFERENCES local_runner_connections(id) ON DELETE SET NULL;
ALTER TABLE executions DROP CONSTRAINT ck_execution_status;
ALTER TABLE executions ADD CONSTRAINT ck_execution_status CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_RUNNER', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'));
ALTER TABLE executions ADD CONSTRAINT ck_execution_mode CHECK (execution_mode IN ('CLOUD_API', 'LOCAL_CLI', 'STUB'));
CREATE INDEX idx_executions_runner_queue ON executions(execution_mode, status, owner_id, queued_at);
