-- Fixes the public status page endpoint (GET /api/v1/status-pages/{slug}) being permanently
-- dead in production. An anonymous visitor has no JWT, so no app.tenant_id can be set before
-- resolving which tenant owns the requested slug -- that resolution is what has to happen
-- BEFORE tenant context can exist at all. V60 added FORCE ROW LEVEL SECURITY to status_pages
-- without carving out this one legitimate, necessary exception: with app.tenant_id unset,
-- "tenant_id = NULL" is never true in SQL, so the existing tenant_isolation policy silently
-- matched zero rows for every slug, unconditionally. See STEP_REPORT.md.
--
-- Fix: a SECOND, narrowly-scoped SELECT-only permissive policy, visible only while a
-- transaction-local flag is set -- and the ONLY thing that ever sets that flag is the function
-- below, which does exactly one thing: given a slug, return its tenant_id and nothing else.
-- Postgres OR's multiple permissive policies together, so this ADDS visibility without
-- touching or weakening the existing tenant_isolation policy; every other query against
-- status_pages (with the flag unset, which is every query except this function's own SELECT)
-- is completely unaffected and remains exactly as tenant-scoped as before.
--
-- Deliberately NOT a SECURITY DEFINER function owned by a BYPASSRLS role -- that would need a
-- new database role with BYPASSRLS, which is a broader, harder-to-audit grant than this table
-- needs for one single-column lookup, and FORCE ROW LEVEL SECURITY means the table owner alone
-- (via SECURITY DEFINER) would NOT bypass RLS anyway, so that approach would not even work
-- without the new role. This needs nothing beyond the ordinary CREATE POLICY / CREATE FUNCTION
-- privilege the owner of this table already has -- no new role, no BYPASSRLS grant anywhere.

-- WARNING to future readers: this policy is PERMISSIVE and gated by a session/transaction-local
-- GUC, not by anything tied to the calling role or caller identity. Postgres OR's it together
-- with tenant_isolation, so ANY session that sets app.allow_status_page_slug_lookup = 'true'
-- (by any means, not just through the function below) sees every row in this table regardless
-- of app.tenant_id. That is safe ONLY because the one column this table's public reader
-- actually needs (tenant_id, keyed by a slug that's already meant to be a public URL) isn't
-- sensitive on its own. Do NOT copy this pattern onto a table where a cross-tenant SELECT would
-- actually matter -- there, the composite (tenant_id, ...) key / normal RLS scoping used
-- everywhere else in this codebase is the correct approach, not a GUC-gated bypass policy.
CREATE POLICY slug_lookup_bypass ON status_pages
    FOR SELECT
    USING (current_setting('app.allow_status_page_slug_lookup', true) = 'true');

COMMENT ON POLICY slug_lookup_bypass ON status_pages IS
    'PERMISSIVE and GUC-gated, not role-scoped -- OR''d together with tenant_isolation, so any session that sets app.allow_status_page_slug_lookup=true sees every tenant''s row. Safe here only because tenant_id keyed by a public slug isn''t sensitive. Do not replicate this pattern on a table where cross-tenant visibility would matter.';

CREATE FUNCTION resolve_status_page_tenant(p_slug VARCHAR)
RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
    result UUID;
BEGIN
    -- is_local = true: this reverts automatically at the end of the current transaction even
    -- if something below throws. The EXCEPTION block below is what makes the reset explicit and
    -- guaranteed by this function itself, rather than incidentally relying on the caller
    -- letting the whole transaction abort to achieve the same effect.
    PERFORM set_config('app.allow_status_page_slug_lookup', 'true', true);
    SELECT tenant_id INTO result FROM status_pages WHERE slug = p_slug;
    PERFORM set_config('app.allow_status_page_slug_lookup', 'false', true);
    RETURN result;
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config('app.allow_status_page_slug_lookup', 'false', true);
    RAISE;
END;
$$;

COMMENT ON FUNCTION resolve_status_page_tenant(VARCHAR) IS
    'Resolves a public status-page slug to its owning tenant_id ONLY -- a single column, no other data. The one sanctioned way to read status_pages without app.tenant_id already set. Resets its bypass flag on both the success AND error path -- see the EXCEPTION block. Backs StatusPageService.resolveTenantIdForSlug -- see that method''s javadoc.';
