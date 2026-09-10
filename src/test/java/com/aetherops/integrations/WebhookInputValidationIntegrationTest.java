package com.aetherops.integrations;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 remediation, findings 3, 12 and 15 (see STEP_REPORT.md): every test here proves a
 * malformed or oversized request no longer produces an uncaught 500.
 *
 * <p>Two distinct client-error statuses are in play, both pre-existing conventions this batch
 * follows rather than invents: {@code @Valid}/{@code @Size} rejections go through
 * {@code GlobalExceptionHandler.handleValidation(MethodArgumentNotValidException)}, which this
 * codebase has always mapped to 422 (see {@code ForgotPasswordRequest}/{@code
 * ResetPasswordRequest}'s existing behavior — finding 15 explicitly says to match their
 * pattern). The new, this-batch {@code DataIntegrityViolationException} handler is a different,
 * newly-added path and returns 400 (or 409 for a genuine unique-constraint conflict), exactly as
 * specified for that handler.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebhookInputValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private static String repeat(int len) {
        return "x".repeat(len);
    }

    // --- Finding 3: reject-via-@Size DTOs, one oversized-field case per provider ---
    // (422 — MethodArgumentNotValidException, this app's established validation-failure status)

    @Test
    void newRelic_oversizedEntityName_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"issueId":"i1","title":"t","priority":"CRITICAL","state":"ACTIVATED",
             "entitiesData":{"entities":[{"name":"%s"}]}}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/new-relic")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sentry_oversizedProject_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"action":"created","data":{"issue":{"title":"t","level":"error","project":"%s"}}}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/sentry")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void rollbar_oversizedProjectName_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"event_name":"new_item","data":{"item":{"title":"t","level":"error","project":{"name":"%s"}}}}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/rollbar")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void opsgenie_oversizedSource_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"action":"Create","alert":{"message":"m","source":"%s","priority":"P3"}}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/opsgenie")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void victorOps_oversizedEntityId_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"message_type":"CRITICAL","entity_id":"%s","state_message":"m"}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/victorops")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void pagerDuty_oversizedServiceSummary_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"event":{"event_type":"incident.triggered","data":{"title":"t","service":{"summary":"%s"}}}}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/pagerduty")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gitHub_oversizedRepositoryFullName_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"deployment_status":{"state":"failure"},
             "deployment":{"environment":"production","sha":"abc123"},
             "repository":{"full_name":"%s"}}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/integrations/github")
                .header("X-Api-Key", apiKey)
                .header("X-GitHub-Event", "deployment_status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void gitHub_oversizedDeploymentEnvironment_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"deployment_status":{"state":"failure"},
             "deployment":{"environment":"%s","sha":"abc123"},
             "repository":{"full_name":"acme/api"}}
            """.formatted(repeat(51));

        mockMvc.perform(post("/api/v1/integrations/github")
                .header("X-Api-Key", apiKey)
                .header("X-GitHub-Event", "deployment_status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void launchDarkly_oversizedName_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"name":"%s","member":{"email":"a@example.com"}}
            """.formatted(repeat(256));

        mockMvc.perform(post("/api/v1/integrations/launchdarkly")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    // --- Finding 3: map-derived fields (Grafana/Alertmanager) truncate rather than reject —
    // proving that choice doesn't 500 either, since Bean Validation cannot reach Map values. ---

    @Test
    void grafana_oversizedServiceLabel_truncatedAndAccepted_notServerError() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"status":"firing","alerts":[{"status":"firing",
             "labels":{"alertname":"HighCpu","service":"%s"},
             "annotations":{"summary":"CPU too high"}}]}
            """.formatted(repeat(150));

        mockMvc.perform(post("/api/v1/integrations/grafana")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isAccepted());
    }

    // --- Finding 12: MuteAlertRequest (422 — same @Valid/@Size path as above) ---

    @Test
    void muteAlert_oversizedServiceId_rejectedNotServerError() throws Exception {
        UUID tenantId = createTenant();
        String body = """
            {"serviceId":"%s","alertName":"high_cpu","reason":"noisy"}
            """.formatted(repeat(101));

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/alert-suppression", tenantId)
                .with(asTenant(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    // --- Finding 15: typed DTOs replacing Map<String,String> bindings (422 — same path) ---

    @Test
    void resendVerification_missingEmail_rejectedNotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void refresh_missingRefreshToken_rejectedNotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void logout_missingRefreshToken_rejectedNotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnprocessableEntity());
    }

    // --- Systemic handler: a constraint violation no DTO validation covers (genuinely 400) ---

    // LaunchDarklyWebhookRequest.name only has @Size (finding 3 chose reject-if-oversized, not
    // reject-if-absent — matching the narrow scope of that finding), so a missing name is NOT
    // caught by Bean Validation at all — this request sails past @Valid untouched. It reaches
    // LaunchDarklyFlagEventService.record(), which maps it straight to
    // LaunchDarklyFlagEvent.flagKey — flag_key VARCHAR(255) NOT NULL
    // (V58__create_launchdarkly_flag_events.sql). Before the systemic handler this was an
    // uncaught 500 (a raw DataIntegrityViolationException); this proves the new handler in
    // GlobalExceptionHandler catches it as a 400 instead — the one case in this file that is
    // NOT going through MethodArgumentNotValidException, hence not 422.
    @Test
    void launchDarkly_missingName_hitsNotNullConstraint_caughtBySystemicHandlerAs400() throws Exception {
        UUID tenantId = createTenant();
        String apiKey = createApiKey(tenantId);
        String body = """
            {"member":{"email":"a@example.com"}}
            """;

        mockMvc.perform(post("/api/v1/integrations/launchdarkly")
                .header("X-Api-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Webhook Validation Test Tenant", 30);
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
                .content(objectMapper.writeValueAsString(new CreateProjectRequest(tenantId, "Webhook Validation Project")))
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
}
