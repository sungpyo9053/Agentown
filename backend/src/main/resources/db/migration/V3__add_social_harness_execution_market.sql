CREATE TABLE user_blocks (
    blocker_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_self CHECK (blocker_id <> blocked_id)
);

CREATE TABLE mini_home_visits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mini_home_id UUID NOT NULL REFERENCES mini_homes(id) ON DELETE CASCADE,
    visitor_id UUID REFERENCES users(id) ON DELETE SET NULL,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mini_home_visits_home_time ON mini_home_visits(mini_home_id, visited_at DESC);

CREATE TABLE agent_definitions (
    agent_id UUID PRIMARY KEY REFERENCES agents(id) ON DELETE CASCADE,
    task_description TEXT NOT NULL,
    desired_output TEXT NOT NULL,
    prohibitions TEXT NOT NULL DEFAULT '',
    input_schema JSONB NOT NULL DEFAULT '{"type":"object","properties":{"input":{"type":"string"}},"required":["input"]}'::jsonb,
    output_schema JSONB NOT NULL DEFAULT '{"type":"object","properties":{"result":{"type":"string"}},"required":["result"]}'::jsonb,
    agent_markdown TEXT NOT NULL,
    guide_markdown TEXT NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE harnesses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_harnesses_visibility CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC', 'MARKET')),
    CONSTRAINT ck_harnesses_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DEPRECATED', 'BLOCKED'))
);
CREATE INDEX idx_harnesses_owner_created ON harnesses(owner_id, created_at DESC);

CREATE TABLE harness_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    harness_id UUID NOT NULL REFERENCES harnesses(id) ON DELETE CASCADE,
    agent_id UUID REFERENCES agents(id) ON DELETE RESTRICT,
    step_key VARCHAR(60) NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    sequence_no INTEGER NOT NULL,
    max_retries INTEGER NOT NULL DEFAULT 0,
    timeout_seconds INTEGER NOT NULL DEFAULT 120,
    requires_approval BOOLEAN NOT NULL DEFAULT false,
    input_mapping JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_harness_step_key UNIQUE (harness_id, step_key),
    CONSTRAINT uq_harness_step_sequence UNIQUE (harness_id, sequence_no),
    CONSTRAINT ck_harness_step_type CHECK (step_type IN ('LLM', 'EXTERNAL_API', 'DOWNLOAD', 'APPROVAL')),
    CONSTRAINT ck_harness_step_retries CHECK (max_retries BETWEEN 0 AND 3),
    CONSTRAINT ck_harness_step_timeout CHECK (timeout_seconds BETWEEN 1 AND 900)
);

CREATE TABLE harness_edges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    harness_id UUID NOT NULL REFERENCES harnesses(id) ON DELETE CASCADE,
    source_step_id UUID NOT NULL REFERENCES harness_steps(id) ON DELETE CASCADE,
    target_step_id UUID REFERENCES harness_steps(id) ON DELETE CASCADE,
    condition_type VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_harness_edge_self CHECK (source_step_id <> target_step_id),
    CONSTRAINT ck_harness_edge_condition CHECK (condition_type IN ('SUCCESS', 'APPROVE', 'REJECT', 'FAILURE'))
);

CREATE TABLE harness_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    harness_id UUID NOT NULL REFERENCES harnesses(id) ON DELETE CASCADE,
    version VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_harness_version UNIQUE (harness_id, version),
    CONSTRAINT ck_harness_version_status CHECK (status IN ('PUBLISHED', 'DEPRECATED', 'BLOCKED'))
);

CREATE TABLE executions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    harness_id UUID NOT NULL REFERENCES harnesses(id) ON DELETE RESTRICT,
    harness_version_id UUID REFERENCES harness_versions(id) ON DELETE RESTRICT,
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    input_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_json JSONB,
    current_step_key VARCHAR(60),
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    queued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    timeout_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_execution_idempotency UNIQUE (owner_id, idempotency_key),
    CONSTRAINT ck_execution_status CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'))
);
CREATE INDEX idx_executions_queue ON executions(status, queued_at);
CREATE INDEX idx_executions_owner_status ON executions(owner_id, status);

CREATE TABLE execution_steps (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    harness_step_id UUID REFERENCES harness_steps(id) ON DELETE SET NULL,
    step_key VARCHAR(60) NOT NULL,
    step_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    input_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_json JSONB,
    provider VARCHAR(30),
    model VARCHAR(100),
    input_tokens BIGINT,
    output_tokens BIGINT,
    estimated_cost NUMERIC(14,6),
    provider_request_id VARCHAR(200),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    error_code VARCHAR(80),
    error_message VARCHAR(1000),
    CONSTRAINT ck_execution_step_status CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT'))
);

CREATE TABLE execution_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    agent_id UUID,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_execution_event_sequence UNIQUE (execution_id, sequence_no)
);

CREATE TABLE artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL REFERENCES executions(id) ON DELETE CASCADE,
    owner_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(150) NOT NULL,
    external_url TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_artifact_status CHECK (status IN ('AVAILABLE', 'EXPIRED', 'FAILED'))
);

CREATE TABLE market_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    harness_version_id UUID NOT NULL UNIQUE REFERENCES harness_versions(id) ON DELETE CASCADE,
    creator_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1500),
    category VARCHAR(60) NOT NULL,
    official BOOLEAN NOT NULL DEFAULT false,
    clone_count BIGINT NOT NULL DEFAULT 0,
    like_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_clones (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES market_products(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cloned_harness_id UUID NOT NULL REFERENCES harnesses(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE product_likes (
    product_id UUID NOT NULL REFERENCES market_products(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (product_id, user_id)
);
