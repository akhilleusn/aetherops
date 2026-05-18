-- M5: evidence items link log events to incidents

CREATE TABLE evidence_items (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id  UUID         NOT NULL,
    tenant_id    UUID         NOT NULL,
    log_event_id UUID         NOT NULL,
    type         VARCHAR(50)  NOT NULL DEFAULT 'LOG_EVENT',
    source_ref   VARCHAR(255) NOT NULL,
    summary      TEXT,
    level        VARCHAR(20),
    timestamp    TIMESTAMPTZ  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_evidence_incident_id        ON evidence_items (incident_id);
CREATE INDEX idx_evidence_tenant_id          ON evidence_items (tenant_id);
CREATE INDEX idx_evidence_incident_timestamp ON evidence_items (incident_id, timestamp DESC);
