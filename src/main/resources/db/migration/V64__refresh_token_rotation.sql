-- Phase 1 Step C, item 4: refresh tokens were stored raw and never rotated — the same token
-- stayed valid, reusable indefinitely, until its 7-day expiry or an explicit revoke. This adds
-- rotation-with-reuse-detection: token_hash replaces the raw token column (only a SHA-256 hash
-- is ever persisted now), family_id groups every token descended from one original login so an
-- entire family can be revoked at once, and used marks a token that has already been rotated
-- away from — presenting one again is the signal a token has leaked.
--
-- Safe to run without a data-migration step: nothing in the application code creates a
-- refresh_tokens row today (RefreshTokenService.createRefreshToken has no caller anywhere in
-- src/main/java — see STEP_REPORT.md), so this table is empty in any real deployment reachable
-- through the app's own code.
ALTER TABLE refresh_tokens RENAME COLUMN token TO token_hash;
ALTER TABLE refresh_tokens ADD COLUMN family_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE refresh_tokens ADD COLUMN used BOOLEAN NOT NULL DEFAULT FALSE;

DROP INDEX IF EXISTS idx_refresh_tokens_token;
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
