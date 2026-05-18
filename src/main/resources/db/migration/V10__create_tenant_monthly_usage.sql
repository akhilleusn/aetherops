-- M9: per-tenant per-month usage counters for AI operations

CREATE TABLE tenant_monthly_usage (
    id                          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID        NOT NULL,
    period                      VARCHAR(7)  NOT NULL,   -- 'YYYY-MM'
    ai_analysis_count           INTEGER     NOT NULL DEFAULT 0,
    runbook_generation_count    INTEGER     NOT NULL DEFAULT 0,
    postmortem_generation_count INTEGER     NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ,
    CONSTRAINT uq_usage_tenant_period UNIQUE (tenant_id, period)
);

CREATE INDEX idx_tenant_monthly_usage_tenant ON tenant_monthly_usage (tenant_id);
