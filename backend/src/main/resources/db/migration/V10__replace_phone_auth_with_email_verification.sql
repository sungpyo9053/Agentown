ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;
UPDATE users SET email_verified_at = created_at WHERE email IS NOT NULL;

CREATE TABLE email_verifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_verifications_email_created
    ON email_verifications(email, created_at DESC);

DROP TABLE phone_verifications;
ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_phone_hash;
ALTER TABLE users DROP COLUMN phone_hash;
ALTER TABLE users DROP COLUMN phone_masked;
ALTER TABLE users DROP COLUMN phone_verified_at;
