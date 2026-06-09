CREATE TABLE idempotency_keys (
                                  id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                                  idempotency_key VARCHAR(255) NOT NULL,
                                  tenant_id       UUID        NOT NULL,
                                  resource_type   VARCHAR(50) NOT NULL,
                                  resource_id     UUID,
                                  response_body   TEXT,
                                  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                  UNIQUE (idempotency_key, tenant_id, resource_type)
);

CREATE INDEX idx_idempotency_keys_key_tenant
    ON idempotency_keys (idempotency_key, tenant_id, resource_type);