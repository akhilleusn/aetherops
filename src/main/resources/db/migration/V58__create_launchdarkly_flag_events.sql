CREATE TABLE launchdarkly_flag_events (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    flag_key            VARCHAR(255) NOT NULL,
    environment         VARCHAR(100),
    change_description  TEXT,
    changed_by          VARCHAR(255),
    occurred_at         TIMESTAMPTZ  NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_launchdarkly_flag_events_tenant_occurred ON launchdarkly_flag_events (tenant_id, occurred_at);
