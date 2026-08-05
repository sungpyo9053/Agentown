CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    handle VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_handle UNIQUE (handle),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'WITHDRAWN')),
    CONSTRAINT ck_users_handle CHECK (handle ~ '^[a-z0-9_]{3,30}$')
);

CREATE TABLE profiles (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    display_name VARCHAR(40) NOT NULL,
    bio VARCHAR(300),
    avatar_url VARCHAR(500),
    visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_profiles_visibility CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC', 'MARKET'))
);

CREATE TABLE mini_homes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(80) NOT NULL,
    introduction VARCHAR(500),
    background_key VARCHAR(60) NOT NULL DEFAULT 'village-day',
    visibility VARCHAR(20) NOT NULL DEFAULT 'PUBLIC',
    visit_count BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_mini_homes_visibility CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC', 'MARKET')),
    CONSTRAINT ck_mini_homes_visit_count CHECK (visit_count >= 0)
);

CREATE TABLE agents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    role VARCHAR(100) NOT NULL,
    personality VARCHAR(500),
    character_key VARCHAR(60) NOT NULL,
    system_prompt TEXT,
    script TEXT NOT NULL,
    guide TEXT,
    model_provider VARCHAR(30) NOT NULL DEFAULT 'OPENAI',
    model_name VARCHAR(80) NOT NULL,
    temperature NUMERIC(3,2) NOT NULL DEFAULT 0.70,
    max_tokens INTEGER NOT NULL DEFAULT 2048,
    visibility VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_agents_visibility CHECK (visibility IN ('PRIVATE', 'FRIENDS', 'PUBLIC', 'MARKET')),
    CONSTRAINT ck_agents_temperature CHECK (temperature >= 0 AND temperature <= 2),
    CONSTRAINT ck_agents_max_tokens CHECK (max_tokens BETWEEN 1 AND 32768)
);

CREATE INDEX idx_agents_owner_created ON agents(owner_id, created_at DESC);

CREATE TABLE room_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mini_home_id UUID NOT NULL REFERENCES mini_homes(id) ON DELETE CASCADE,
    agent_id UUID REFERENCES agents(id) ON DELETE CASCADE,
    asset_key VARCHAR(100),
    item_type VARCHAR(20) NOT NULL,
    position_x NUMERIC(7,6) NOT NULL,
    position_y NUMERIC(7,6) NOT NULL,
    width NUMERIC(7,6) NOT NULL,
    height NUMERIC(7,6) NOT NULL,
    z_index INTEGER NOT NULL DEFAULT 0,
    rotation NUMERIC(7,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_room_items_agent UNIQUE (mini_home_id, agent_id),
    CONSTRAINT ck_room_items_type CHECK (item_type IN ('AGENT', 'ASSET')),
    CONSTRAINT ck_room_items_source CHECK (
        (item_type = 'AGENT' AND agent_id IS NOT NULL AND asset_key IS NULL) OR
        (item_type = 'ASSET' AND agent_id IS NULL AND asset_key IS NOT NULL)
    ),
    CONSTRAINT ck_room_items_x CHECK (position_x BETWEEN 0 AND 1),
    CONSTRAINT ck_room_items_y CHECK (position_y BETWEEN 0 AND 1),
    CONSTRAINT ck_room_items_width CHECK (width > 0 AND width <= 1),
    CONSTRAINT ck_room_items_height CHECK (height > 0 AND height <= 1)
);

CREATE INDEX idx_room_items_home_z ON room_items(mini_home_id, z_index);

CREATE TABLE friendships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    addressee_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    responded_at TIMESTAMPTZ,
    CONSTRAINT uq_friendship_pair UNIQUE (requester_id, addressee_id),
    CONSTRAINT ck_friendship_self CHECK (requester_id <> addressee_id),
    CONSTRAINT ck_friendship_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED'))
);

