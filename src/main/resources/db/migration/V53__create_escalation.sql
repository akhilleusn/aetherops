CREATE TABLE escalation_policies (
    id                      UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id               UUID         NOT NULL,
    service_id              VARCHAR(100),
    severity_threshold      VARCHAR(20)  NOT NULL DEFAULT 'CRITICAL',
    escalate_after_minutes  INT          NOT NULL DEFAULT 5,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ
);

-- One policy per (tenant, service); service_id IS NULL represents the tenant-wide default policy.
CREATE UNIQUE INDEX idx_escalation_policies_tenant_service
    ON escalation_policies (tenant_id, COALESCE(service_id, ''));

CREATE TABLE escalation_executions (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    incident_id         UUID         NOT NULL,
    policy_id           UUID,
    escalation_level    INT          NOT NULL DEFAULT 1,
    escalated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_escalation_executions_incident ON escalation_executions (incident_id);
CREATE INDEX idx_escalation_executions_tenant ON escalation_executions (tenant_id);
