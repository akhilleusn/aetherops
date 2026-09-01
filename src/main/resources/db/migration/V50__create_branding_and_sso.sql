CREATE TABLE tenant_branding (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    company_name     VARCHAR(200),
    logo_url         VARCHAR(500),
    primary_color    VARCHAR(7),
    secondary_color  VARCHAR(7),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tenant_branding_tenant ON tenant_branding (tenant_id);

CREATE TABLE tenant_sso_config (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
    idp_entity_id    VARCHAR(500) NOT NULL,
    idp_sso_url      VARCHAR(500) NOT NULL,
    idp_certificate  TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tenant_sso_config_tenant ON tenant_sso_config (tenant_id);
