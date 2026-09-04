CREATE TABLE builder_activity_events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id),
    event_type VARCHAR(80) NOT NULL,
    target_type VARCHAR(40),
    target_id UUID,
    outcome VARCHAR(20) NOT NULL,
    http_status INTEGER NOT NULL,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_builder_activity_events_created_at
    ON builder_activity_events(created_at DESC);

CREATE INDEX idx_builder_activity_events_workspace_created
    ON builder_activity_events(workspace_id, created_at DESC);
