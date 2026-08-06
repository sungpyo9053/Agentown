ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ADD COLUMN phone_hash VARCHAR(64);
ALTER TABLE users ADD COLUMN phone_masked VARCHAR(20);
ALTER TABLE users ADD COLUMN phone_verified_at TIMESTAMPTZ;
ALTER TABLE users ADD CONSTRAINT uq_users_phone_hash UNIQUE (phone_hash);

CREATE TABLE phone_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_hash VARCHAR(64) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_phone_verifications_phone_created
    ON phone_verifications(phone_hash, created_at DESC);
