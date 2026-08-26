CREATE TABLE notion_page_write_requests (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id),
    connection_id UUID NOT NULL REFERENCES connector_connections(id),
    idempotency_key VARCHAR(120) NOT NULL,
    approval_idempotency_key VARCHAR(120),
    parent_page_id VARCHAR(120) NOT NULL,
    title VARCHAR(200) NOT NULL,
    content_json JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    notion_page_id VARCHAR(120),
    notion_url VARCHAR(500),
    failure_code VARCHAR(80),
    failure_message VARCHAR(500),
    approved_by UUID,
    approved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_notion_write_preview UNIQUE (workspace_id, idempotency_key),
    CONSTRAINT uq_notion_write_approval UNIQUE (workspace_id, approval_idempotency_key),
    CONSTRAINT ck_notion_write_status CHECK (status IN ('PREVIEWED','APPROVED','PUBLISHING','SUCCEEDED','FAILED'))
);

CREATE INDEX idx_notion_write_workspace_created ON notion_page_write_requests(workspace_id, created_at DESC);
