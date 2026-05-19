-- M11: tenant-scoped audit log for AI operations

CREATE TABLE audit_logs (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL,
    action        VARCHAR(50) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    resource_type VARCHAR(50),
    resource_id   UUID,
    message       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_tenant_id      ON audit_logs (tenant_id);
CREATE INDEX idx_audit_logs_tenant_created ON audit_logs (tenant_id, created_at DESC);
CREATE INDEX idx_audit_logs_action         ON audit_logs (action);
