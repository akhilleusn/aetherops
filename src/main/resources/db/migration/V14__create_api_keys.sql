CREATE TABLE api_keys (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    project_id  UUID         NOT NULL,
    description VARCHAR(255),
    key_prefix  VARCHAR(16)  NOT NULL,
    key_hash    VARCHAR(64)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_api_keys_key_hash   ON api_keys (key_hash);
CREATE        INDEX idx_api_keys_tenant_id  ON api_keys (tenant_id);
CREATE        INDEX idx_api_keys_project_id ON api_keys (project_id);
