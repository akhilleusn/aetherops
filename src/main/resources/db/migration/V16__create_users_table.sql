CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(60)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'MEMBER',
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email     ON users (email);
CREATE INDEX idx_users_tenant_id ON users (tenant_id);
