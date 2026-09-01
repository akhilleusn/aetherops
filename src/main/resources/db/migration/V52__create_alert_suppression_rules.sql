CREATE TABLE alert_suppression_rules (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    service_id       VARCHAR(100),
    alert_name       VARCHAR(255),
    muted_until      TIMESTAMPTZ,
    reason           VARCHAR(500),
    created_by       VARCHAR(255),
    auto_generated   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_alert_suppression_rules_tenant ON alert_suppression_rules (tenant_id);
