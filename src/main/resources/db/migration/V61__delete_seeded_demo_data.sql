-- Removes the demo user/tenant/incidents seeded by DemoDataInitializer (deleted as part of
-- Phase 1 remediation — the demo tenant's well-known credentials and its public,
-- unauthenticated JWT-minting endpoint were a takeover vector). New deployments never seed
-- this data again; this cleans up data already written to databases that ran with
-- demo.mode=true.
--
-- incident_embeddings cascades automatically when its incident row is deleted (V34: ON
-- DELETE CASCADE). users.tenant_id references tenants(id) (V16), so users must be deleted
-- before the tenant. All statements are no-ops if the demo data was never seeded.

DELETE FROM refresh_tokens
    WHERE user_id IN (SELECT id FROM users WHERE email = 'demo@aetherops.io');

DELETE FROM incidents
    WHERE tenant_id IN (SELECT id FROM tenants WHERE name = 'AetherOps Demo');

DELETE FROM users WHERE email = 'demo@aetherops.io';

DELETE FROM tenants
    WHERE name = 'AetherOps Demo'
      AND NOT EXISTS (SELECT 1 FROM users WHERE users.tenant_id = tenants.id);
