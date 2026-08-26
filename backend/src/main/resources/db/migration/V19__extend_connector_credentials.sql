ALTER TABLE connector_connections
    ADD COLUMN encrypted_refresh_token TEXT,
    ADD COLUMN last_verified_at TIMESTAMPTZ;
