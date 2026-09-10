package com.aetherops.usage;

import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.usage.repository.TenantMonthlyUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   objectMapper;
    @Autowired TenantRepository               tenantRepository;
    @Autowired TenantMonthlyUsageRepository   usageRepository;
    @Autowired LogEventRepository             logEventRepository;
    @Autowired IncidentRepository             incidentRepository;
    @Autowired EvidenceItemRepository         evidenceItemRepository;
    @Autowired AIAnalysisRepository           aiAnalysisRepository;
    @Autowired RunbookSuggestionRepository    runbookSuggestionRepository;
    @Autowired PostmortemDraftRepository      postmortemDraftRepository;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        postmortemDraftRepository.deleteAll();
        runbookSuggestionRepository.deleteAll();
        aiAnalysisRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        usageRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // (a) AI analysis call increments monthly usage counter
    @Test
    void aiAnalysis_incrementsMonthlyUsage() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId);

        analyzeOnce(incidentId, tenantId);

        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.used").value(1))
            .andExpect(jsonPath("$.data.usage.RUNBOOK_GENERATION.used").value(0))
            .andExpect(jsonPath("$.data.usage.POSTMORTEM_GENERATION.used").value(0));
    }

    // (b) Runbook generation call increments monthly usage counter
    @Test
    void runbook_incrementsMonthlyUsage() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId);

        generateRunbook(incidentId, tenantId);

        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usage.RUNBOOK_GENERATION.used").value(1))
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.used").value(0))
            .andExpect(jsonPath("$.data.usage.POSTMORTEM_GENERATION.used").value(0));
    }

    // (c) Postmortem generation call increments monthly usage counter
    @Test
    void postmortem_incrementsMonthlyUsage() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId);

        generatePostmortem(incidentId, tenantId);

        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usage.POSTMORTEM_GENERATION.used").value(1))
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.used").value(0))
            .andExpect(jsonPath("$.data.usage.RUNBOOK_GENERATION.used").value(0));
    }

    // (d) FREE plan blocks AI analysis after the monthly limit of 5
    @Test
    void freePlan_aiAnalysis_blocksAfterLimit() throws Exception {
        UUID tenantId   = createTenant(Plan.FREE);
        UUID incidentId = createIncidentForTenant(tenantId);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                    .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
                .andExpect(status().isOk());
        }

        // 6th request must be blocked with HTTP 429 and standard ErrorResponse shape
        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.error").value("Too Many Requests"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // (e) Blocked request does not increment the usage counter
    @Test
    void blockedRequest_doesNotIncrementUsage() throws Exception {
        UUID tenantId   = createTenant(Plan.FREE);
        UUID incidentId = createIncidentForTenant(tenantId);

        for (int i = 0; i < 5; i++) {
            analyzeOnce(incidentId, tenantId);
        }

        // Trigger the 429
        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isTooManyRequests());

        // Count must still be 5, not 6
        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.used").value(5));
    }

    // (f) Usage is per-tenant: exhausting tenant A's limit does not affect tenant B
    @Test
    void tenantIsolation_usageIsPerTenant() throws Exception {
        UUID tenantA    = createTenant(Plan.FREE);
        UUID tenantB    = createTenant(Plan.FREE);
        UUID incidentA  = createIncidentForTenant(tenantA);
        UUID incidentB  = createIncidentForTenant(tenantB);

        // Exhaust tenant A
        for (int i = 0; i < 5; i++) {
            analyzeOnce(incidentA, tenantA);
        }
        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentA)
                .param("tenantId", tenantA.toString()).with(asTenant(tenantA)))
            .andExpect(status().isTooManyRequests());

        // Tenant B should still be unaffected
        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentB)
                .param("tenantId", tenantB.toString()).with(asTenant(tenantB)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantB).with(asTenant(tenantB)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.used").value(1));
    }

    // (g) GET usage summary returns correct counts and plan limits
    @Test
    void getUsageSummary_correctCountsAndLimits() throws Exception {
        UUID tenantId   = createTenant(Plan.FREE);
        UUID incidentId = createIncidentForTenant(tenantId);

        analyzeOnce(incidentId, tenantId);
        analyzeOnce(incidentId, tenantId);
        generateRunbook(incidentId, tenantId);

        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.data.plan").value("FREE"))
            .andExpect(jsonPath("$.data.period").isNotEmpty())
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.used").value(2))
            .andExpect(jsonPath("$.data.usage.AI_ANALYSIS.limit").value(5))
            .andExpect(jsonPath("$.data.usage.RUNBOOK_GENERATION.used").value(1))
            .andExpect(jsonPath("$.data.usage.RUNBOOK_GENERATION.limit").value(10))
            .andExpect(jsonPath("$.data.usage.POSTMORTEM_GENERATION.used").value(0))
            .andExpect(jsonPath("$.data.usage.POSTMORTEM_GENERATION.limit").value(5));
    }

    // (h) TEAM plan operations are not blocked (limits are high enough for normal use)
    @Test
    void teamPlan_notBlockedByLimits() throws Exception {
        UUID tenantId   = createTenant(Plan.TEAM);
        UUID incidentId = createIncidentForTenant(tenantId);

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk());
    }

    // (i) Usage period stored in the DB equals YearMonth.now() (current calendar month)
    @Test
    void usagePeriod_isCurrentYearMonth() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId);

        analyzeOnce(incidentId, tenantId);

        String expectedPeriod = YearMonth.now().toString();
        var records = withTenant(tenantId, () -> usageRepository.findAll());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getPeriod()).isEqualTo(expectedPeriod);

        // Also verify the GET endpoint returns the same period
        mockMvc.perform(get("/api/v1/tenants/{id}/usage", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.period").value(expectedPeriod));
    }

    // (j) GET usage for an unknown tenant returns 404 with the standard ErrorResponse shape
    @Test
    void getUsage_unknownTenant_returns404() throws Exception {
        UUID unknownTenantId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        mockMvc.perform(get("/api/v1/tenants/{id}/usage", unknownTenantId)
                .with(asTenant(unknownTenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID createTenant(Plan plan) throws Exception {
        // Phase 2 remediation, finding 7 (see STEP_REPORT.md): the API always creates a FREE
        // tenant now -- plan is no longer client-settable. This test needs a specific plan to
        // exercise plan-dependent behavior, so it's set directly via the repository, bypassing
        // the API for test setup (the same principle as the fix itself: plan changes happen
        // through a trusted path, never client-declared self-service).
        var request = new CreateTenantRequest("Test Tenant", 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID tenantId = UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setPlan(plan);
        tenantRepository.save(tenant);

        return tenantId;
    }

    private UUID createIncidentForTenant(UUID tenantId) throws Exception {
        ingestEvent(tenantId, "test-svc", LogLevel.ERROR, "Service error for usage test");
        return UUID.fromString(
            objectMapper.readTree(
                mockMvc.perform(get("/api/v1/incidents").param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
                    .andReturn().getResponse().getContentAsString()
            ).at("/data/content/0/id").asText()
        );
    }

    private void analyzeOnce(UUID incidentId, UUID tenantId) throws Exception {
        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk());
    }

    private void generateRunbook(UUID incidentId, UUID tenantId) throws Exception {
        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk());
    }

    private void generatePostmortem(UUID incidentId, UUID tenantId) throws Exception {
        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk());
    }

    private void ingestEvent(UUID tenantId, String serviceId, LogLevel level, String message)
        throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId(serviceId);
        req.setLevel(level);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }
}
