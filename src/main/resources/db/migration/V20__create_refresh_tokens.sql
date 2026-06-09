CREATE TABLE refresh_tokens (
                                id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                                token       VARCHAR(512) NOT NULL UNIQUE,
                                user_id     UUID        NOT NULL,
                                tenant_id   UUID        NOT NULL,
                                expires_at  TIMESTAMPTZ NOT NULL,
                                revoked     BOOLEAN     NOT NULL DEFAULT FALSE,
                                created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_token   ON refresh_tokens (token);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);