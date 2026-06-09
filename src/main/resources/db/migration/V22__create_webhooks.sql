CREATE TABLE webhook_endpoints (
                                   id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                                   tenant_id   UUID        NOT NULL,
                                   url         VARCHAR(512) NOT NULL,
                                   secret      VARCHAR(255),
                                   active      BOOLEAN     NOT NULL DEFAULT TRUE,
                                   created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                   updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE webhook_deliveries (
                                    id                  UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                                    webhook_endpoint_id UUID        NOT NULL REFERENCES webhook_endpoints(id),
                                    event_type          VARCHAR(100) NOT NULL,
                                    payload             TEXT        NOT NULL,
                                    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                    attempts            INT         NOT NULL DEFAULT 0,
                                    last_attempt_at     TIMESTAMPTZ,
                                    next_retry_at       TIMESTAMPTZ,
                                    response_status     INT,
                                    response_body       TEXT,
                                    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_endpoints_tenant ON webhook_endpoints (tenant_id);
CREATE INDEX idx_webhook_deliveries_endpoint ON webhook_deliveries (webhook_endpoint_id);
CREATE INDEX idx_webhook_deliveries_status ON webhook_deliveries (status);