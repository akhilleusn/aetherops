-- Dedup table for Stripe webhook deliveries (Stripe may redeliver the same
-- event on timeout/retry; event ids are globally unique per Stripe account).
CREATE TABLE stripe_webhook_events (
    event_id     VARCHAR(255) NOT NULL PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
