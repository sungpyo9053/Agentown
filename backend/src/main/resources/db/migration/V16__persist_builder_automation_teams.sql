CREATE TABLE builder_automation_teams (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES builder_workspaces(id) ON DELETE CASCADE,
    workflow_id UUID NOT NULL REFERENCES builder_workflows(id) ON DELETE CASCADE,
    workflow_version_id UUID NOT NULL UNIQUE REFERENCES builder_workflow_versions(id) ON DELETE RESTRICT,
    name VARCHAR(80) NOT NULL,
    category VARCHAR(40) NOT NULL DEFAULT '업무 자동화',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_builder_automation_teams_workspace ON builder_automation_teams(workspace_id, created_at DESC);

CREATE TABLE builder_automation_team_members (
    id UUID PRIMARY KEY,
    team_id UUID NOT NULL REFERENCES builder_automation_teams(id) ON DELETE CASCADE,
    agent_id UUID NOT NULL UNIQUE REFERENCES agents(id) ON DELETE CASCADE,
    agent_key VARCHAR(60) NOT NULL,
    sequence_no INTEGER NOT NULL,
    agent_markdown TEXT NOT NULL,
    guide_markdown TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_builder_automation_team_agent_key UNIQUE(team_id, agent_key),
    CONSTRAINT uq_builder_automation_team_sequence UNIQUE(team_id, sequence_no)
);
CREATE INDEX idx_builder_automation_team_members_team ON builder_automation_team_members(team_id, sequence_no);
