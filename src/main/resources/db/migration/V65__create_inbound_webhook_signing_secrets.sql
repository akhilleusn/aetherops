-- Phase 1 Step E, item 2 (finding 5): per-tenant opt-in signing secrets for the inbound
-- alert-source webhooks that support provider-side request signing (GitHub, PagerDuty,
-- Grafana). header_name/timestamp_header_name are only meaningful for Grafana, whose
-- signature header (and optional replay-timestamp header) name is configurable on the
-- sending side; NULL there means "use the provider's documented default".
--
-- AWS SNS (the cloudwatch endpoint) is deliberately NOT represented here — its signing is
-- certificate-based and mandatory on every message, not a shared secret a tenant configures.

CREATE TABLE inbound_webhook_signing_secrets (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id              UUID         NOT NULL,
    provider               VARCHAR(20)  NOT NULL,
    encrypted_secret       TEXT         NOT NULL,
    header_name            VARCHAR(100),
    timestamp_header_name  VARCHAR(100),
    enabled                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_inbound_webhook_signing_secrets_tenant_provider
    ON inbound_webhook_signing_secrets (tenant_id, provider);

ALTER TABLE inbound_webhook_signing_secrets ENABLE ROW LEVEL SECURITY;
ALTER TABLE inbound_webhook_signing_secrets FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON inbound_webhook_signing_secrets
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
