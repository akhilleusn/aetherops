-- AetherOps initial schema
-- Milestone 1: log ingestion + incident management

CREATE TABLE log_events (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id   VARCHAR(100) NOT NULL,
    environment  VARCHAR(50),
    level        VARCHAR(20)  NOT NULL,
    message      TEXT         NOT NULL,
    raw_payload  TEXT,
    trace_id     VARCHAR(128),
    span_id      VARCHAR(128),
    occurred_at  TIMESTAMPTZ  NOT NULL,
    ingested_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_log_events_service_id   ON log_events (service_id);
CREATE INDEX idx_log_events_level        ON log_events (level);
CREATE INDEX idx_log_events_occurred_at  ON log_events (occurred_at DESC);
CREATE INDEX idx_log_events_trace_id     ON log_events (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_log_events_service_env  ON log_events (service_id, environment);

-- ---------------------------------------------------------------

CREATE TABLE incidents (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title        VARCHAR(500) NOT NULL,
    description  TEXT,
    status       VARCHAR(50)  NOT NULL DEFAULT 'OPEN',
    severity     VARCHAR(20)  NOT NULL,
    service_id   VARCHAR(100) NOT NULL,
    environment  VARCHAR(50),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ,
    resolved_at  TIMESTAMPTZ
);

CREATE INDEX idx_incidents_status      ON incidents (status);
CREATE INDEX idx_incidents_severity    ON incidents (severity);
CREATE INDEX idx_incidents_service_id  ON incidents (service_id);
CREATE INDEX idx_incidents_created_at  ON incidents (created_at DESC);
