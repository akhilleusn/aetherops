-- Phase 3 finding 2 (see STEP_REPORT.md): email verification tokens were the only bearer
-- token in this codebase stored and looked up in plaintext -- password reset tokens
-- (password_reset_tokens.token_hash), refresh tokens (refresh_tokens.token_hash, see
-- V64__refresh_token_rotation.sql), invitation tokens (invitations.token_hash) and API keys
-- (api_keys.key_hash) are all SHA-256 hashed already. This brings email verification in line:
-- only the hash is ever persisted now, the raw token exists solely in the email sent to the
-- user.
--
-- Safe to run without a data-migration step, same as V64's rename: no real rows exist in any
-- deployed environment (see STEP_REPORT.md, Phase 3 finding 1/G2 -- the Railway account is
-- suspended and nothing has ever been deployed), and nothing else in the schema references
-- this column (no foreign key, no other index) -- confirmed against
-- V33__add_email_verification.sql, the only other migration that touches it.
ALTER TABLE users RENAME COLUMN email_verification_token TO email_verification_token_hash;

DROP INDEX IF EXISTS idx_users_email_verification_token;
CREATE UNIQUE INDEX idx_users_email_verification_token_hash
    ON users (email_verification_token_hash)
    WHERE email_verification_token_hash IS NOT NULL;
