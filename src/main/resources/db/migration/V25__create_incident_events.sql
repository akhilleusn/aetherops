CREATE TABLE incident_events (
                                 id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                                 incident_id  UUID        NOT NULL,
                                 tenant_id    UUID        NOT NULL,
                                 event_type   VARCHAR(100) NOT NULL,
                                 payload      TEXT        NOT NULL,
                                 sequence     BIGINT      NOT NULL,
                                 occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                 UNIQUE (incident_id, sequence)
);

CREATE INDEX idx_incident_events_incident_id ON incident_events (incident_id, sequence);
CREATE INDEX idx_incident_events_tenant_id   ON incident_events (tenant_id);