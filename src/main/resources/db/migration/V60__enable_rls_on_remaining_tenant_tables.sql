-- Phase 2: extend Row Level Security (see V28) to every remaining tenant-scoped table.
-- Same pattern as V28: ENABLE + FORCE (app connects as the table owner, so FORCE is
-- required for RLS to apply to it at all) + a USING policy against app.tenant_id,
-- which TenantContextInterceptor sets via set_config(..., true) for every
-- @Transactional call.
--
-- NOT included: "users". Login and registration must look up a row by email alone,
-- before any tenant is known — that's literally what the lookup determines — so a
-- blanket tenant_id RLS policy would make login/duplicate-email checks always see zero
-- rows. Tenant isolation for user-management endpoints stays enforced at the
-- application layer (TenantContext.requireOwn(), added in Phase 1) instead of at the DB.
--
-- NOT included: "api_keys". Same reason — ApiKeyAuthFilter resolves a caller's tenant
-- FROM the key by looking it up by its hash, cross-tenant, before any tenant context
-- exists. RLS would make that lookup always see zero rows too.
--
-- NOT included: "refresh_tokens". Same reason again — POST /auth/refresh validates a
-- refresh token by its value alone (that's the whole point: the access token/JWT has
-- already expired, so there is no tenant context to set before the lookup).
--
-- NOT included: "slack_workspaces". Inbound Slack webhooks (SlackInteractivityController,
-- SlackCommandController) are authenticated by Slack's own request signature, not a JWT,
-- and resolve the tenant FROM Slack's team_id via a cross-tenant findByTeamId lookup —
-- no tenant context exists before that lookup runs.

ALTER TABLE tenant_monthly_usage        ENABLE ROW LEVEL SECURITY;
ALTER TABLE projects                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE services                    ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys            ENABLE ROW LEVEL SECURITY;
ALTER TABLE usage_records               ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_events             ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_reports            ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_embeddings         ENABLE ROW LEVEL SECURITY;
ALTER TABLE oncall_rotation             ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_timeline           ENABLE ROW LEVEL SECURITY;
ALTER TABLE deduplicated_alerts         ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_templates          ENABLE ROW LEVEL SECURITY;
ALTER TABLE status_pages                ENABLE ROW LEVEL SECURITY;
ALTER TABLE status_page_incident_updates ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_branding             ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_sso_config           ENABLE ROW LEVEL SECURITY;
ALTER TABLE push_device_tokens          ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_suppression_rules     ENABLE ROW LEVEL SECURITY;
ALTER TABLE escalation_policies         ENABLE ROW LEVEL SECURITY;
ALTER TABLE escalation_executions       ENABLE ROW LEVEL SECURITY;
ALTER TABLE war_rooms                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_calendar_connections   ENABLE ROW LEVEL SECURITY;
ALTER TABLE incident_cost_entries       ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_cost_settings        ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_ticket_integrations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE launchdarkly_flag_events    ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_items              ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_analyses                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE runbook_suggestions         ENABLE ROW LEVEL SECURITY;
ALTER TABLE postmortem_drafts           ENABLE ROW LEVEL SECURITY;
ALTER TABLE log_events                  ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_monthly_usage        FORCE ROW LEVEL SECURITY;
ALTER TABLE projects                    FORCE ROW LEVEL SECURITY;
ALTER TABLE services                    FORCE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys            FORCE ROW LEVEL SECURITY;
ALTER TABLE usage_records               FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_events             FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_reports            FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_embeddings         FORCE ROW LEVEL SECURITY;
ALTER TABLE oncall_rotation             FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_timeline           FORCE ROW LEVEL SECURITY;
ALTER TABLE deduplicated_alerts         FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_templates          FORCE ROW LEVEL SECURITY;
ALTER TABLE status_pages                FORCE ROW LEVEL SECURITY;
ALTER TABLE status_page_incident_updates FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_branding             FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_sso_config           FORCE ROW LEVEL SECURITY;
ALTER TABLE push_device_tokens          FORCE ROW LEVEL SECURITY;
ALTER TABLE alert_suppression_rules     FORCE ROW LEVEL SECURITY;
ALTER TABLE escalation_policies         FORCE ROW LEVEL SECURITY;
ALTER TABLE escalation_executions       FORCE ROW LEVEL SECURITY;
ALTER TABLE war_rooms                   FORCE ROW LEVEL SECURITY;
ALTER TABLE user_calendar_connections   FORCE ROW LEVEL SECURITY;
ALTER TABLE incident_cost_entries       FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_cost_settings        FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_ticket_integrations  FORCE ROW LEVEL SECURITY;
ALTER TABLE launchdarkly_flag_events    FORCE ROW LEVEL SECURITY;
ALTER TABLE evidence_items              FORCE ROW LEVEL SECURITY;
ALTER TABLE ai_analyses                 FORCE ROW LEVEL SECURITY;
ALTER TABLE runbook_suggestions         FORCE ROW LEVEL SECURITY;
ALTER TABLE postmortem_drafts           FORCE ROW LEVEL SECURITY;
ALTER TABLE log_events                  FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON tenant_monthly_usage
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON projects
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON services
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON idempotency_keys
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON usage_records
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON incident_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON incident_reports
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON incident_embeddings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON oncall_rotation
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON incident_timeline
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON deduplicated_alerts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- incident_templates.tenant_id is nullable: NULL rows are global system templates,
-- visible to every tenant, so they must pass the policy regardless of app.tenant_id.
CREATE POLICY tenant_isolation ON incident_templates
    USING (tenant_id IS NULL OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_isolation ON status_pages
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON status_page_incident_updates
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tenant_branding
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tenant_sso_config
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON push_device_tokens
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON alert_suppression_rules
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON escalation_policies
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON escalation_executions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON war_rooms
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON user_calendar_connections
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON incident_cost_entries
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tenant_cost_settings
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON tenant_ticket_integrations
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON launchdarkly_flag_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON evidence_items
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON ai_analyses
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON runbook_suggestions
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON postmortem_drafts
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY tenant_isolation ON log_events
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
