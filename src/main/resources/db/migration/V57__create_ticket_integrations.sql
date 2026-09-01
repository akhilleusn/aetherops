CREATE TABLE tenant_ticket_integrations (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id              UUID         NOT NULL,
    provider               VARCHAR(20)  NOT NULL,
    base_url               VARCHAR(500) NOT NULL,
    project_key            VARCHAR(50),
    encrypted_api_token    TEXT         NOT NULL,
    username               VARCHAR(255),
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tenant_ticket_integrations_tenant_provider ON tenant_ticket_integrations (tenant_id, provider);
