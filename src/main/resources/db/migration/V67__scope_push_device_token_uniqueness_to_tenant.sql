-- Fixes a cross-tenant collision bug (see STEP_REPORT.md): two different tenants registering
-- the SAME physical device's Expo push token threw a 500. The unique index was global
-- (expo_push_token alone) while push_device_tokens' RLS policy scopes every query to the
-- caller's own tenant_id -- so tenant B's upsert-lookup never saw tenant A's existing row for
-- that token (RLS hid it), fell through to an INSERT, and collided with it anyway (unique
-- indexes are enforced against the whole physical table regardless of what a session's RLS
-- policy lets it see).
--
-- A device CAN legitimately be registered under more than one tenant at once (e.g. an on-call
-- engineer supporting two different client orgs on the same phone) -- so the fix is not to
-- evict/reassign the older registration, it's to scope uniqueness to (tenant_id,
-- expo_push_token) instead of expo_push_token alone, matching what PushNotificationService's
-- upsert lookup now also scopes by.

DROP INDEX idx_push_device_tokens_token;

CREATE UNIQUE INDEX idx_push_device_tokens_tenant_token
    ON push_device_tokens (tenant_id, expo_push_token);
