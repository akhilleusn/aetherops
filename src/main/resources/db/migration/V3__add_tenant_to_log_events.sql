-- M2: scope log events by tenant and optional project

ALTER TABLE log_events
    ADD COLUMN tenant_id  UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    ADD COLUMN project_id UUID;

-- Remove the sentinel default so future inserts must supply a real tenant_id
ALTER TABLE log_events ALTER COLUMN tenant_id DROP DEFAULT;

CREATE INDEX idx_log_events_tenant_id      ON log_events (tenant_id);
CREATE INDEX idx_log_events_tenant_service ON log_events (tenant_id, service_id);
CREATE INDEX idx_log_events_tenant_level   ON log_events (tenant_id, level);
