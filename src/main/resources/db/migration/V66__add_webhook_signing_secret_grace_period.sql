-- Phase 1 Step E, item 2 (finding 5) migration follow-up: supports a transition window for
-- secrets a migration backfills on a tenant's behalf (LegacyWebhookSecretBackfillRunner),
-- as opposed to one a tenant/operator configures directly (which has no need for a grace
-- period — it's correct from the moment it's set).
--
-- NULL (the default, and always the case for a manually configured secret) means "enforce
-- strictly, as before": a missing/invalid signature is rejected the moment a row exists.
-- A non-null future timestamp means WebhookSignatureFilter treats a failed check as
-- "not yet configured on the sender's side" (falls back to API-key-only auth, logged) rather
-- than a 401, until that time passes — giving the tenant time to actually add the generated
-- secret to their sending side before enforcement begins for real.

ALTER TABLE inbound_webhook_signing_secrets
    ADD COLUMN grace_period_ends_at TIMESTAMPTZ;
