-- Feature 1: PostgreSQL Row Level Security
-- Enables tenant isolation at the database level via SET LOCAL app.tenant_id

ALTER TABLE incidents        ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_logs       ENABLE ROW LEVEL SECURITY;
ALTER TABLE webhook_endpoints ENABLE ROW LEVEL SECURITY;

-- Superuser / service-role bypass (app runs as the DB owner, so FORCE is needed)
ALTER TABLE incidents        FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_logs       FORCE ROW LEVEL SECURITY;
ALTER TABLE webhook_endpoints FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON incidents
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON audit_logs
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON webhook_endpoints
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
