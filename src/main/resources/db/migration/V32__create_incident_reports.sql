-- Feature 10: Scheduled Report Generation

CREATE TABLE incident_reports (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end   TIMESTAMPTZ NOT NULL,
    report_json  TEXT        NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_incident_reports_tenant     ON incident_reports (tenant_id);
CREATE INDEX idx_incident_reports_period_end ON incident_reports (tenant_id, period_end DESC);
