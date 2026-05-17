-- Tenants represent customer/team accounts in AetherOps

CREATE TABLE tenants (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(100) NOT NULL,
    plan                    VARCHAR(20)  NOT NULL,
    ai_monthly_budget_cents INTEGER      NOT NULL DEFAULT 0,
    retention_days          INTEGER      NOT NULL DEFAULT 30,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ
);

CREATE INDEX idx_tenants_plan       ON tenants (plan);
CREATE INDEX idx_tenants_created_at ON tenants (created_at DESC);
