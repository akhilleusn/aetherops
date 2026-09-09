package com.aetherops.integrations.webhook;

import com.aetherops.AbstractIntegrationTest;
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
import java.util.UUID;

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

    private static String hmacHex(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
