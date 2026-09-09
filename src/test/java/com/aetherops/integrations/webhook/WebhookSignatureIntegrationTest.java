package com.aetherops.integrations.webhook;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.project.model.CreateProjectRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 Step E, item 2 (finding 5): end-to-end signature verification for the three
 * approved providers (GitHub, PagerDuty, Grafana), exercised through the real filter chain
 * (MockMvc, not a direct controller call) since WebhookSignatureFilter — not the
 * controller — is what actually enforces this.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookSignatureIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired WebhookSigningSecretService signingSecretService;
    @Autowired IncidentRepository incidentRepository;

    private static final String BODY = "{}";
    private static final String SECRET = "correct-horse-battery-staple";

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // --- GitHub ---

    @Test
    void github_validSignature_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GITHUB, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/github")
                .header("X-Api-Key", apiKey)
                .header("X-Hub-Signature-256", "sha256=" + hmacHex(SECRET, BODY))
                .header("X-GitHub-Event", "deployment_status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void github_invalidSignature_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GITHUB, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/github")
                .header("X-Api-Key", apiKey)
                .header("X-Hub-Signature-256", "sha256=" + hmacHex("wrong-secret", BODY))
                .header("X-GitHub-Event", "deployment_status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void github_missingSignature_secretConfigured_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GITHUB, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/github")
                .header("X-Api-Key", apiKey)
                .header("X-GitHub-Event", "deployment_status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void github_noSecretConfigured_fallsBackToApiKeyOnly_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        // No signing secret configured for this tenant — existing X-Api-Key-only behavior
        // must be unaffected.

        mockMvc.perform(post("/api/v1/integrations/github")
                .header("X-Api-Key", apiKey)
                .header("X-GitHub-Event", "deployment_status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    // --- PagerDuty ---

    @Test
    void pagerDuty_validSignature_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.PAGERDUTY, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/pagerduty")
                .header("X-Api-Key", apiKey)
                .header("X-PagerDuty-Signature", "v1=" + hmacHex(SECRET, BODY))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void pagerDuty_invalidSignature_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.PAGERDUTY, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/pagerduty")
                .header("X-Api-Key", apiKey)
                .header("X-PagerDuty-Signature", "v1=" + hmacHex("wrong-secret", BODY))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void pagerDuty_missingSignature_secretConfigured_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.PAGERDUTY, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/pagerduty")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    // Secret rotation: PagerDuty can send old+new signatures comma-separated. Only the
    // SECOND value matches here — a naive single-value comparison would reject this.
    @Test
    void pagerDuty_multiSignatureHeader_onlySecondValueMatches_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.PAGERDUTY, SECRET, null, null);

        String staleSignature = "v1=" + hmacHex("old-rotated-out-secret", BODY);
        String currentSignature = "v1=" + hmacHex(SECRET, BODY);
        String multiValueHeader = staleSignature + ", " + currentSignature;

        mockMvc.perform(post("/api/v1/integrations/pagerduty")
                .header("X-Api-Key", apiKey)
                .header("X-PagerDuty-Signature", multiValueHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    // --- Grafana ---

    @Test
    void grafana_validSignature_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GRAFANA, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .header("X-Grafana-Alerting-Signature", hmacHex(SECRET, BODY))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void grafana_invalidSignature_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GRAFANA, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .header("X-Grafana-Alerting-Signature", hmacHex("wrong-secret", BODY))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void grafana_missingSignature_secretConfigured_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GRAFANA, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    // Grafana's header name is configurable per-installation; a tenant that configured a
    // custom header must be checked under that name, not the default.
    @Test
    void grafana_customHeaderName_validSignature_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GRAFANA, SECRET, "X-Custom-Grafana-Sig", null);

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .header("X-Custom-Grafana-Sig", hmacHex(SECRET, BODY))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    // Grafana optional timestamp mode: signature is over "timestamp:body", and a timestamp
    // outside the replay window must be rejected even with an otherwise-correct signature.
    @Test
    void grafana_timestampMode_validSignatureWithinWindow_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GRAFANA, SECRET, null, "X-Grafana-Timestamp");

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = hmacHex(SECRET, timestamp + ":" + BODY);

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .header("X-Grafana-Alerting-Signature", signature)
                .header("X-Grafana-Timestamp", timestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void grafana_timestampMode_outsideReplayWindow_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.GRAFANA, SECRET, null, "X-Grafana-Timestamp");

        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String signature = hmacHex(SECRET, staleTimestamp + ":" + BODY);

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .header("X-Grafana-Alerting-Signature", signature)
                .header("X-Grafana-Timestamp", staleTimestamp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    // --- Static-token providers: Datadog, New Relic, Opsgenie, Rollbar, Alertmanager ---
    // No signature exists for any of these (E2 recon) — the "secret" is a plain shared value
    // carried in a header (or for Rollbar, its own documented URL query param), checked with
    // a constant-time compare. Splunk deliberately has none of this — see the accepted-risk
    // test further down.

    private static final String DATADOG_BODY = "{\"serviceId\":\"svc\",\"title\":\"High error rate\"}";

    @Test
    void datadog_validStaticToken_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void datadog_wrongStaticToken_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", "wrong-value")
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void datadog_missingStaticToken_secretConfigured_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void newRelic_validStaticToken_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.NEW_RELIC, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/new-relic")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void newRelic_wrongStaticToken_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.NEW_RELIC, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/new-relic")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", "wrong-value")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void opsgenie_validStaticToken_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.OPSGENIE, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/opsgenie")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void opsgenie_missingStaticToken_secretConfigured_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.OPSGENIE, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/opsgenie")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    // Rollbar's own documented convention is a URL query token, not a header.
    @Test
    void rollbar_validStaticToken_inQueryParam_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.ROLLBAR, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/rollbar")
                .header("X-Api-Key", apiKey)
                .param("auth", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void rollbar_wrongQueryToken_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.ROLLBAR, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/rollbar")
                .header("X-Api-Key", apiKey)
                .param("auth", "wrong-value")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void rollbar_missingQueryToken_secretConfigured_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.ROLLBAR, SECRET, null, null);

        mockMvc.perform(post("/api/v1/integrations/rollbar")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void alertmanager_validStaticToken_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.ALERTMANAGER, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/prometheus/alertmanager")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void alertmanager_wrongStaticToken_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.ALERTMANAGER, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/prometheus/alertmanager")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", "wrong-value")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());
    }

    // --- Cross-provider secret isolation (explicitly requested) ---

    @Test
    void datadogSecret_doesNotAuthenticateOnOpsgenieEndpoint_evenWithBothConfigured() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String datadogSecret = "datadog-only-secret";
        String opsgenieSecret = "opsgenie-only-secret";
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, datadogSecret, null, null);
        configureSecret(tenantId, WebhookSigningProvider.OPSGENIE, opsgenieSecret, null, null);

        // The Datadog secret, presented where Opsgenie's secret is expected: rejected.
        mockMvc.perform(post("/api/v1/integrations/opsgenie")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", datadogSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isUnauthorized());

        // Control: Opsgenie's own secret on Opsgenie's endpoint still works.
        mockMvc.perform(post("/api/v1/integrations/opsgenie")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", opsgenieSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
            .andExpect(status().isAccepted());
    }

    // Splunk has no static-token mechanism at all (accepted risk — see the comment on
    // IntegrationsWebhookController.splunk(...)). A webhook-signing secret from another
    // provider must not work as a substitute API key there either.
    @Test
    void datadogSecret_rejectedWhenUsedAsApiKeyOnSplunkEndpoint() throws Exception {
        UUID tenantId = createTenant();
        createApiKey(tenantId); // establishes the tenant/project; the real key is unused here
        String datadogSecret = "datadog-only-secret";
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, datadogSecret, null, null);

        mockMvc.perform(post("/api/v1/integrations/splunk")
                .header("X-Api-Key", datadogSecret)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"search_name\":\"test-search\"}"))
            .andExpect(status().isUnauthorized());
    }

    // --- Replay guard (Datadog/New Relic/Opsgenie/Rollbar/Alertmanager only) ---

    @Test
    void datadog_replayedFingerprintWithinWindow_rejectedAndNotIngested() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());

        // Immediate resend of the identical alert (same tenant/service/title -> same
        // fingerprint) — rejected as a replay even though the signature itself is valid.
        // Still ack'd 202 to the sender (a resend isn't the sender's fault to see as an
        // error) — the assertion that matters is that it was never actually ingested.
        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());

        List<Incident> incidents = withTenant(tenantId, () -> incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN));
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getEventCount()).isEqualTo(1); // NOT 2 — the replay never reached ingest()
    }

    @Test
    void datadog_legitimateRefireAfterWindow_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        configureSecret(tenantId, WebhookSigningProvider.DATADOG, SECRET, null, null);

        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());

        Thread.sleep(1200); // test's replay window is overridden to 1s (see AbstractIntegrationTest)

        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", SECRET)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());

        List<Incident> incidents = withTenant(tenantId, () -> incidentRepository.findByTenantIdAndStatus(tenantId, IncidentStatus.OPEN));
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getEventCount()).isEqualTo(2); // the re-fire WAS ingested this time
    }

    // --- Migration grace period (LegacyWebhookSecretBackfillRunner) ---

    @Test
    void datadog_migrationBackfilledSecret_withinGracePeriod_missingToken_fallsBackToApiKeyOnly_accepted() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        backfillSecret(tenantId, WebhookSigningProvider.DATADOG, Instant.now().plusSeconds(3600));

        // The tenant hasn't added the generated secret to their Datadog config yet — no
        // X-Webhook-Secret header at all. Still within the grace period, so this must NOT be
        // rejected the way a normally-configured-and-missing secret would be.
        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());
    }

    @Test
    void datadog_migrationBackfilledSecret_gracePeriodExpired_missingToken_rejected() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        backfillSecret(tenantId, WebhookSigningProvider.DATADOG, Instant.now().minusSeconds(1));

        // Grace period already elapsed — the tenant had their window to configure the secret
        // and didn't (from this request's perspective); enforcement is now strict, same as any
        // other configured secret.
        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void datadog_migrationBackfilledSecret_correctTokenPresented_acceptedRegardlessOfGracePeriod() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String secret = backfillSecret(tenantId, WebhookSigningProvider.DATADOG, Instant.now().plusSeconds(3600));

        // Once the tenant HAS adopted the generated secret, it works immediately — the grace
        // period only softens a FAILED check, it never blocks a correct one.
        mockMvc.perform(post("/api/v1/monitoring/datadog")
                .header("X-Api-Key", apiKey)
                .header("X-Webhook-Secret", secret)
                .contentType(MediaType.APPLICATION_JSON)
                .content(DATADOG_BODY))
            .andExpect(status().isAccepted());
    }

    // --- helpers ---

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Webhook Sig Test Tenant", Plan.STARTER, 0, 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private String createApiKey(UUID tenantId) throws Exception {
        String projectBody = mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateProjectRequest(tenantId, "Webhook Sig Project")))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID projectId = UUID.fromString(objectMapper.readTree(projectBody).at("/data/id").asText());

        String keyBody = mockMvc.perform(post("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(keyBody).at("/data/plaintext").asText();
    }

    private void configureSecret(UUID tenantId, WebhookSigningProvider provider, String secret,
                                  String headerName, String timestampHeaderName) {
        withTenant(tenantId, () ->
            signingSecretService.configure(tenantId, provider, secret, headerName, timestampHeaderName));
    }

    /** Simulates what LegacyWebhookSecretBackfillRunner does for one (tenant, provider) — a
     *  migration-generated secret with a grace period, as opposed to configureSecret's
     *  operator/tenant-configured (grace-period-less) secret. Returns the plaintext secret. */
    private String backfillSecret(UUID tenantId, WebhookSigningProvider provider, Instant gracePeriodEndsAt) {
        String secret = "backfilled-" + UUID.randomUUID();
        withTenant(tenantId, () -> signingSecretService.backfillIfAbsent(tenantId, provider, secret, gracePeriodEndsAt));
        return secret;
    }

    private static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
