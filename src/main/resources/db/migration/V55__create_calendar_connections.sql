CREATE TABLE user_calendar_connections (
    id                        UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id                   UUID         NOT NULL,
    tenant_id                 UUID         NOT NULL,
    provider                  VARCHAR(20)  NOT NULL,
    encrypted_refresh_token   TEXT         NOT NULL,
    calendar_id               VARCHAR(255),
    connected_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_synced_at            TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_user_calendar_connections_user_provider ON user_calendar_connections (user_id, provider);
CREATE INDEX idx_user_calendar_connections_tenant ON user_calendar_connections (tenant_id);
