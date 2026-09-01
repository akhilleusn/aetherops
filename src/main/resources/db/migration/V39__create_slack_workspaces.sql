CREATE TABLE slack_workspaces (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id            UUID         NOT NULL,
    team_id              VARCHAR(20)  NOT NULL,
    team_name            VARCHAR(200),
    bot_user_id          VARCHAR(20)  NOT NULL,
    encrypted_bot_token  TEXT         NOT NULL,
    scope                VARCHAR(500),
    installed_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_slack_workspaces_tenant ON slack_workspaces (tenant_id);
CREATE UNIQUE INDEX idx_slack_workspaces_team ON slack_workspaces (team_id);
