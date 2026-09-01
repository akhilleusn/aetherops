CREATE TABLE war_rooms (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id         UUID         NOT NULL,
    incident_id       UUID         NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    opened_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    opened_by         VARCHAR(20)  NOT NULL,
    closed_at         TIMESTAMPTZ,
    slack_channel_id  VARCHAR(50),
    slack_thread_ts   VARCHAR(50)
);

CREATE INDEX idx_war_rooms_tenant ON war_rooms (tenant_id);
CREATE INDEX idx_war_rooms_incident ON war_rooms (incident_id);

-- At most one ACTIVE war room per incident; CLOSED war rooms may accumulate history freely.
CREATE UNIQUE INDEX idx_war_rooms_incident_active ON war_rooms (incident_id) WHERE status = 'ACTIVE';

CREATE TABLE war_room_participants (
    id            UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    war_room_id   UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    role          VARCHAR(30)  NOT NULL,
    joined_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    left_at       TIMESTAMPTZ
);

-- No tenant_id column here; tenant scoping is transitive via war_rooms.tenant_id.
CREATE INDEX idx_war_room_participants_war_room ON war_room_participants (war_room_id);

-- A user can't be an active (not-yet-left) participant twice in the same war room.
CREATE UNIQUE INDEX idx_war_room_participants_active ON war_room_participants (war_room_id, user_id) WHERE left_at IS NULL;
