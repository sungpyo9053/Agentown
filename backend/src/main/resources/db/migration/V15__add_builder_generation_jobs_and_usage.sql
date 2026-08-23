CREATE TABLE builder_generation_jobs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES builder_conversations(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id) ON DELETE CASCADE,
    instruction TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    stage VARCHAR(40) NOT NULL,
    estimated_seconds INTEGER NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    error_code VARCHAR(80),
    error_message VARCHAR(500),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_generation_job_idempotency UNIQUE(workspace_id, idempotency_key)
);
CREATE INDEX idx_builder_generation_jobs_conversation ON builder_generation_jobs(conversation_id, created_at DESC);

CREATE TABLE builder_usage_records (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES builder_conversations(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id) ON DELETE CASCADE,
    usage_type VARCHAR(40) NOT NULL,
    limit_slot VARCHAR(20),
    idempotency_key VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_usage_limit_slot UNIQUE(owner_id, usage_type, limit_slot),
    CONSTRAINT uq_builder_usage_idempotency UNIQUE(owner_id, idempotency_key)
);
CREATE INDEX idx_builder_usage_owner ON builder_usage_records(owner_id, created_at DESC);

ALTER TABLE builder_meta_agent_runs ADD COLUMN failure_summary JSONB;
