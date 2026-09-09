-- Server-issued invitations: the only way a new registrant may join an existing tenant
-- (see AuthService.register / GoogleAuthService.authenticate). Redemption matches on
-- token + email; self-serve registration with no token always creates a brand-new tenant
-- instead of letting the caller pick one.
--
-- Only the SHA-256 hash of the token is stored (see InvitationTokenGenerator) so a
-- database dump never yields a usable invitation; the raw token exists only in the
-- link handed to the invitee at issuance time.
--
-- NOT RLS-protected, same reasoning as users/api_keys/refresh_tokens (see V60): an
-- invitation is looked up by its token hash alone, before any tenant context exists.
--
-- No issuance endpoint exists yet as of this migration — rows are created directly
-- (e.g. by tests, or a future admin-facing endpoint). Redemption is fully wired.
CREATE TABLE invitations (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,
    tenant_id    UUID         NOT NULL REFERENCES tenants(id),
    email        VARCHAR(255) NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    expires_at   TIMESTAMPTZ  NOT NULL,
    redeemed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invitations_tenant_id ON invitations (tenant_id);
CREATE INDEX idx_invitations_email     ON invitations (email);
