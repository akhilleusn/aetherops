CREATE TABLE incident_timeline (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL,
    incident_id  UUID        NOT NULL,
    event_type   VARCHAR(50) NOT NULL,
    severity     VARCHAR(20),
    detail       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (id),
    PRIMARY KEY (incident_id, created_at)
);

CREATE INDEX idx_incident_timeline_tenant ON incident_timeline (tenant_id);
