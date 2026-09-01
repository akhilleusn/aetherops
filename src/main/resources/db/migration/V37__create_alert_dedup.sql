CREATE TABLE deduplicated_alerts (
    id             UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    fingerprint    VARCHAR(64) NOT NULL,
    service        VARCHAR(100) NOT NULL,
    alert_name     VARCHAR(200) NOT NULL,
    severity       VARCHAR(20) NOT NULL,
    incident_id    UUID,
    alert_count    INTEGER     NOT NULL DEFAULT 1,
    first_seen_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_deduplicated_alerts_tenant_fingerprint ON deduplicated_alerts (tenant_id, fingerprint);
