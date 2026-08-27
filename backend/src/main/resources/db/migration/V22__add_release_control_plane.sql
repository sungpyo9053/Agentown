CREATE TABLE releases (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE RESTRICT,
    release_key VARCHAR(80) NOT NULL,
    purpose VARCHAR(300) NOT NULL,
    user_summary VARCHAR(500) NOT NULL,
    status VARCHAR(40) NOT NULL,
    risk_level VARCHAR(20) NOT NULL,
    current_sha VARCHAR(40),
    candidate_sha VARCHAR(40) NOT NULL,
    included_task_count INTEGER NOT NULL,
    has_migration BOOLEAN NOT NULL DEFAULT FALSE,
    staging_status VARCHAR(40) NOT NULL,
    scheduled_at TIMESTAMPTZ,
    approval_idempotency_key VARCHAR(120),
    approval_environment VARCHAR(30),
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    approval_preflight_hash VARCHAR(64),
    preflight_hash VARCHAR(64) NOT NULL,
    actual_deployed_sha VARCHAR(40),
    uncertain_outcome BOOLEAN NOT NULL DEFAULT FALSE,
    detail_json JSONB NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_release_workspace_key UNIQUE(workspace_id, release_key),
    CONSTRAINT uq_release_approval_idempotency UNIQUE(workspace_id, approval_idempotency_key)
);
CREATE INDEX idx_releases_workspace_status ON releases(workspace_id, status, created_at DESC);

CREATE TABLE release_events (
    id UUID PRIMARY KEY,
    release_id UUID NOT NULL REFERENCES releases(id) ON DELETE RESTRICT,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE RESTRICT,
    actor_id UUID,
    actor_label VARCHAR(120) NOT NULL,
    previous_status VARCHAR(40),
    next_status VARCHAR(40) NOT NULL,
    commit_sha VARCHAR(40) NOT NULL,
    result VARCHAR(40) NOT NULL,
    report_path VARCHAR(500),
    reason VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_release_events_release_time ON release_events(release_id, created_at);
