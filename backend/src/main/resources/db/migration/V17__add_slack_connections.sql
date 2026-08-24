CREATE TABLE connector_oauth_states (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    provider VARCHAR(30) NOT NULL,
    state_hash VARCHAR(64) NOT NULL UNIQUE,
    redirect_uri VARCHAR(500) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_connector_oauth_state_expiry ON connector_oauth_states(expires_at);

CREATE TABLE connector_connections (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    provider VARCHAR(30) NOT NULL,
    external_account_id VARCHAR(120) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    encrypted_access_token TEXT NOT NULL,
    key_version VARCHAR(30) NOT NULL,
    scopes JSONB NOT NULL,
    metadata_json JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_connector_workspace_account UNIQUE(workspace_id, provider, external_account_id)
);
CREATE INDEX idx_connector_provider_account ON connector_connections(provider, external_account_id, status);

CREATE TABLE connector_events (
    id UUID PRIMARY KEY,
    connection_id UUID NOT NULL REFERENCES connector_connections(id) ON DELETE CASCADE,
    provider_event_id VARCHAR(160) NOT NULL UNIQUE,
    event_type VARCHAR(80) NOT NULL,
    channel_id VARCHAR(120),
    actor_external_id VARCHAR(120),
    message_ts VARCHAR(80),
    thread_ts VARCHAR(80),
    payload_json JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_connector_events_connection_received ON connector_events(connection_id, received_at DESC);
