ALTER TABLE incidents ADD COLUMN acknowledged_at          TIMESTAMPTZ;
ALTER TABLE incidents ADD COLUMN response_sla_breached     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE incidents ADD COLUMN resolution_sla_breached   BOOLEAN NOT NULL DEFAULT FALSE;
