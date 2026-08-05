CREATE TABLE llm_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider VARCHAR(20) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    masked_secret VARCHAR(100) NOT NULL,
    key_version VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    provider_options JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_llm_credentials_provider CHECK (provider IN ('OPENAI', 'ANTHROPIC', 'GOOGLE')),
    CONSTRAINT ck_llm_credentials_status CHECK (status IN ('UNVERIFIED', 'ACTIVE', 'INVALID', 'REVOKED'))
);

CREATE INDEX idx_llm_credentials_owner_provider ON llm_credentials(owner_id, provider);

ALTER TABLE agents
    ADD COLUMN credential_id UUID REFERENCES llm_credentials(id) ON DELETE SET NULL,
    ADD COLUMN timeout_seconds INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN provider_options JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE agents RENAME COLUMN max_tokens TO max_output_tokens;
ALTER TABLE agents ADD CONSTRAINT ck_agents_timeout CHECK (timeout_seconds BETWEEN 1 AND 600);

