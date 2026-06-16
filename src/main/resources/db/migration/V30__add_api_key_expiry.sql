-- Feature 7: API Key Authentication — add expiry and last-used tracking

ALTER TABLE api_keys ADD COLUMN expires_at   TIMESTAMPTZ;
ALTER TABLE api_keys ADD COLUMN last_used_at TIMESTAMPTZ;

CREATE INDEX idx_api_keys_expires_at ON api_keys (expires_at) WHERE expires_at IS NOT NULL;
