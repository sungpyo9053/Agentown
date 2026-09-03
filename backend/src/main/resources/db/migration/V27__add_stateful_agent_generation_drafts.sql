CREATE TABLE builder_agent_generation_drafts (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL UNIQUE REFERENCES builder_conversations(id),
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id),
    source_instruction TEXT NOT NULL,
    design_mode VARCHAR(40) NOT NULL,
    state VARCHAR(40) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 0,
    bundle_json JSONB,
    validation_issues_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_message VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_builder_agent_generation_drafts_workflow
    ON builder_agent_generation_drafts(workflow_id);
