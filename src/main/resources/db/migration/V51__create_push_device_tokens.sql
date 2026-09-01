CREATE TABLE push_device_tokens (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id          UUID         NOT NULL,
    tenant_id        UUID         NOT NULL,
    expo_push_token  VARCHAR(255) NOT NULL,
    platform         VARCHAR(20),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_push_device_tokens_token ON push_device_tokens (expo_push_token);
CREATE INDEX idx_push_device_tokens_tenant ON push_device_tokens (tenant_id);
