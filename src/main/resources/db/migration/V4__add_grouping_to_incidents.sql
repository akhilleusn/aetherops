-- M4: add tenant scoping and auto-grouping fields to incidents

ALTER TABLE incidents
    ADD COLUMN tenant_id      UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000000',
    ADD COLUMN project_id     UUID,
    ADD COLUMN fingerprint    VARCHAR(64)  NOT NULL DEFAULT '',
    ADD COLUMN event_count    INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN first_seen_at  TIMESTAMPTZ,
    ADD COLUMN last_seen_at   TIMESTAMPTZ,
    ADD COLUMN latest_event_id UUID;

-- Backfill timestamps from existing rows so NOT NULL can be set
UPDATE incidents
   SET first_seen_at = created_at,
       last_seen_at  = COALESCE(updated_at, created_at)
 WHERE first_seen_at IS NULL;

ALTER TABLE incidents ALTER COLUMN tenant_id   DROP DEFAULT;
ALTER TABLE incidents ALTER COLUMN fingerprint DROP DEFAULT;
ALTER TABLE incidents ALTER COLUMN first_seen_at SET NOT NULL;
ALTER TABLE incidents ALTER COLUMN last_seen_at  SET NOT NULL;

CREATE INDEX idx_incidents_tenant_id                   ON incidents (tenant_id);
CREATE INDEX idx_incidents_tenant_fingerprint_status   ON incidents (tenant_id, fingerprint, status);
CREATE INDEX idx_incidents_tenant_severity             ON incidents (tenant_id, severity);
