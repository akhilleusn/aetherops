CREATE TABLE usage_records (
                               id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                               tenant_id   UUID        NOT NULL,
                               meter       VARCHAR(50) NOT NULL,
                               quantity    BIGINT      NOT NULL DEFAULT 1,
                               recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_usage_records_tenant_meter ON usage_records (tenant_id, meter, recorded_at);