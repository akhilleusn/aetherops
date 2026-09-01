-- V35 shipped oncall_rotation.tenant_id as BIGINT, but every other tenant-scoped
-- table (tenants.id, incidents.tenant_id, etc.) uses UUID. Safe as a drop/add
-- since no code path has written rows to this table yet.
ALTER TABLE oncall_rotation DROP COLUMN tenant_id;
ALTER TABLE oncall_rotation ADD COLUMN tenant_id UUID NOT NULL;

DROP INDEX IF EXISTS idx_oncall_rotation_tenant_service;
CREATE INDEX idx_oncall_rotation_tenant_service ON oncall_rotation (tenant_id, service_id);
