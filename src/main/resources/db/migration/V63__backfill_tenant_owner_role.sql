-- Phase 1 Step B, item 4: TenantOnboardingService previously created each tenant's admin
-- with Role.MEMBER instead of Role.OWNER (fixed in application code in this same pass).
--
-- Restricted to tenants with exactly one user: there, that user is definitionally the
-- tenant's admin (no one else on the tenant to confuse them with), so promoting them to
-- OWNER is a fact, not a guess. There is no column anywhere linking a user row to "the
-- onboarding record" — TenantOnboardingService stamps nothing distinctive on the row it
-- creates — so for a tenant with more than one user and no OWNER, picking "whoever has the
-- earliest created_at" would be a guess: it could promote the wrong person if the true
-- original admin's row was later deleted, or if two users were created close enough in time
-- that created_at doesn't reliably order them (tie-broken by a random UUID, which carries no
-- ordering information at all). Multi-user tenants with no OWNER, if any exist, are
-- deliberately left untouched by this migration and need manual review instead.
--
-- Idempotent: once a tenant has an OWNER, the HAVING clause excludes it on a re-run.
WITH single_user_tenants_without_owner AS (
    SELECT tenant_id
    FROM users
    GROUP BY tenant_id
    HAVING COUNT(*) = 1 AND COUNT(*) FILTER (WHERE role = 'OWNER') = 0
)
UPDATE users
SET role = 'OWNER'
WHERE tenant_id IN (SELECT tenant_id FROM single_user_tenants_without_owner);
