ALTER TABLE incidents ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE INDEX idx_incidents_deleted_at ON incidents (deleted_at);