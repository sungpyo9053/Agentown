CREATE TABLE builder_workspaces (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_workspace_owner UNIQUE(owner_id)
);

CREATE TABLE builder_conversations (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_builder_conversations_workspace ON builder_conversations(workspace_id, updated_at DESC);

CREATE TABLE builder_messages (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES builder_conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    workflow_version_id UUID,
    idempotency_key VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_builder_message_idempotency ON builder_messages(conversation_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE builder_requirements (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL UNIQUE REFERENCES builder_conversations(id) ON DELETE CASCADE,
    structured_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE builder_proposals (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL UNIQUE REFERENCES builder_conversations(id) ON DELETE CASCADE,
    proposal_json JSONB NOT NULL,
    agent_definitions_json JSONB NOT NULL,
    guide_definitions_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE builder_workflows (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL UNIQUE REFERENCES builder_conversations(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(40) NOT NULL,
    current_version_id UUID,
    approved_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_builder_workflows_workspace ON builder_workflows(workspace_id, updated_at DESC);

CREATE TABLE builder_workflow_versions (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    parent_version_id UUID REFERENCES builder_workflow_versions(id) ON DELETE RESTRICT,
    graph_json JSONB NOT NULL,
    graph_hash VARCHAR(64) NOT NULL,
    change_summary VARCHAR(500) NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_workflow_version UNIQUE(workflow_id, version_no)
);
CREATE INDEX idx_builder_versions_workflow ON builder_workflow_versions(workflow_id, version_no DESC);

ALTER TABLE builder_workflows ADD CONSTRAINT fk_builder_current_version
    FOREIGN KEY(current_version_id) REFERENCES builder_workflow_versions(id) ON DELETE RESTRICT;
ALTER TABLE builder_workflows ADD CONSTRAINT fk_builder_approved_version
    FOREIGN KEY(approved_version_id) REFERENCES builder_workflow_versions(id) ON DELETE RESTRICT;

CREATE TABLE builder_approvals (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id) ON DELETE CASCADE,
    run_id UUID,
    approval_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL,
    decided_by UUID REFERENCES users(id) ON DELETE SET NULL,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_approval_idempotency UNIQUE(workspace_id, idempotency_key)
);

CREATE TABLE builder_runs (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id) ON DELETE CASCADE,
    workflow_version_id UUID NOT NULL REFERENCES builder_workflow_versions(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL,
    input_json JSONB NOT NULL,
    output_json JSONB,
    current_node_id VARCHAR(100),
    idempotency_key VARCHAR(120) NOT NULL,
    requirement_matched BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_run_idempotency UNIQUE(workspace_id, idempotency_key)
);

CREATE TABLE builder_step_runs (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES builder_runs(id) ON DELETE CASCADE,
    node_id VARCHAR(100) NOT NULL,
    node_type VARCHAR(80) NOT NULL,
    sequence_no INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    input_json JSONB NOT NULL,
    output_json JSONB,
    error_message VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_step_sequence UNIQUE(run_id, sequence_no)
);

CREATE TABLE builder_meta_agent_runs (
    id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL REFERENCES builder_conversations(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL,
    stage VARCHAR(60) NOT NULL,
    status VARCHAR(20) NOT NULL,
    input_summary JSONB NOT NULL,
    output_summary JSONB,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_builder_meta_trace ON builder_meta_agent_runs(trace_id, created_at);
