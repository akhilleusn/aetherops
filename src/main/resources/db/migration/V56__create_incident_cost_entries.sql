CREATE TABLE incident_cost_entries (
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id     UUID         NOT NULL,
    incident_id   UUID         NOT NULL,
    description   VARCHAR(500) NOT NULL,
    amount_cents  BIGINT       NOT NULL,
    currency      VARCHAR(3)   NOT NULL DEFAULT 'USD',
    source        VARCHAR(20)  NOT NULL,
    recorded_by   VARCHAR(255),
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_incident_cost_entries_tenant_incident ON incident_cost_entries (tenant_id, incident_id);

CREATE TABLE tenant_cost_settings (
    id                          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                   UUID        NOT NULL,
    hourly_responder_rate_cents BIGINT      NOT NULL DEFAULT 15000,
    updated_at                  TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_tenant_cost_settings_tenant ON tenant_cost_settings (tenant_id);
