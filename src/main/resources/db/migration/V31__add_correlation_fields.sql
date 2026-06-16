-- Feature 9: Incident Correlation Engine
-- correlation_group_id groups related incidents that occurred close in time

ALTER TABLE incidents ADD COLUMN correlation_group_id UUID;

CREATE INDEX idx_incidents_correlation_group ON incidents (correlation_group_id)
    WHERE correlation_group_id IS NOT NULL;
