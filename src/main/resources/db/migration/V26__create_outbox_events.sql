CREATE TABLE outbox_events (
    id             UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ
);

CREATE INDEX idx_outbox_events_status_created ON outbox_events (status, created_at);
CREATE INDEX idx_outbox_events_aggregate_id   ON outbox_events (aggregate_id);
