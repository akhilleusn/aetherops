package com.aetherops.analysis;

import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aetherops.AbstractIntegrationTest;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class AIAnalysisIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AIAnalysisRepository aiAnalysisRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired LogEventRepository logEventRepository;

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        aiAnalysisRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
    }

    // (a) POST analyze creates and returns a structured AI analysis
    @Test
    void analyze_createsAndReturnsAnalysis() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Database connection pool exhausted");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()))
            .andExpect(jsonPath("$.data.summary").isNotEmpty())
            .andExpect(jsonPath("$.data.likelyCause").isNotEmpty())
            .andExpect(jsonPath("$.data.model").value("mock-v1"))
            .andExpect(jsonPath("$.data.promptVersion").value("1.0"))
            .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    // (b) Tenant mismatch returns 404
    @Test
    void analyze_tenantMismatch_returns404() throws Exception {
        ingestEvent(TENANT_B, "auth-svc", LogLevel.ERROR, "Token validation failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isNotFound());
    }

    // (c) evidenceRefs contains real evidence item IDs from the evidence pack
    @Test
    void analyze_evidenceRefsContainRealEvidenceItemIds() throws Exception {
        ingestEvent(TENANT_A, "order-svc", LogLevel.ERROR, "Order state machine failed");
        UUID incidentId    = incidentRepository.findAll().get(0).getId();
        String evidenceId  = evidenceItemRepository.findAll().get(0).getId().toString();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode evidenceRefs = objectMapper.readTree(body).at("/data/evidenceRefs");
        assertThat(evidenceRefs.isArray()).isTrue();
        assertThat(evidenceRefs.size()).isGreaterThan(0);

        List<String> refs = objectMapper.convertValue(evidenceRefs, List.class);
        assertThat(refs).contains(evidenceId);
    }

    // (d) unknowns clearly mention missing metrics, traces, and deploy data
    @Test
    void analyze_unknownsReflectMissingDataSources() throws Exception {
        ingestEvent(TENANT_A, "inventory-svc", LogLevel.ERROR, "Stock level read failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String unknowns = objectMapper.readTree(body).at("/data/unknowns").toString().toLowerCase();
        assertThat(unknowns).contains("metrics");
        assertThat(unknowns).contains("traces");
        assertThat(unknowns).contains("deployment");
    }

    // (e) confidenceScore is low (≤ 0.50) for a single weak log event
    @Test
    void analyze_confidenceIsLowForSingleWeakEvidence() throws Exception {
        ingestEvent(TENANT_A, "cache-svc", LogLevel.ERROR, "Cache miss");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        double confidence = objectMapper.readTree(body).at("/data/confidenceScore").asDouble();
        assertThat(confidence).isLessThanOrEqualTo(0.50);
    }

    // (f) recommendedNextChecks contain only safe, non-destructive actions
    @Test
    void analyze_recommendedChecksAreSafeAndNonDestructive() throws Exception {
        ingestEvent(TENANT_A, "billing-svc", LogLevel.ERROR, "Charge processing failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String checks = objectMapper.readTree(body).at("/data/recommendedNextChecks").toString().toLowerCase();
        assertThat(checks).doesNotContain("delete");
        assertThat(checks).doesNotContain("drop");
        assertThat(checks).doesNotContain("restart");
        assertThat(checks).doesNotContain("kill");
        assertThat(checks).doesNotContain("truncate");
        assertThat(checks).doesNotContain("remove");
    }

    // (g) Analysis does not expose raw secrets — masked token must not appear anywhere in response
    @Test
    void analyze_doesNotExposeRawSecrets() throws Exception {
        String rawToken = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        ingestEvent(TENANT_A, "api-gateway", LogLevel.ERROR, "Auth failed: Bearer " + rawToken);
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(rawToken);
        assertThat(body).contains("[REDACTED]");
    }

    // (h) Repeated analyze calls each create a new analysis row (append-only)
    @Test
    void analyze_repeatedCallCreatesNewVersionEachTime() throws Exception {
        ingestEvent(TENANT_A, "search-svc", LogLevel.ERROR, "Index query timeout");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body1 = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String body2 = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String id1 = objectMapper.readTree(body1).at("/data/id").asText();
        String id2 = objectMapper.readTree(body2).at("/data/id").asText();

        assertThat(id1).isNotEqualTo(id2);
        assertThat(aiAnalysisRepository.count()).isEqualTo(2);

        var analyses = aiAnalysisRepository.findByIncidentIdOrderByCreatedAtDesc(incidentId);
        assertThat(analyses).hasSize(2);
        assertThat(analyses).extracting("incidentId").containsOnly(incidentId);
    }

    // (i) First call has analysisVersion=1 and evidenceCount matches ingested events
    @Test
    void analyze_firstCallHasVersionOneAndCorrectEvidenceCount() throws Exception {
        ingestEvent(TENANT_A, "worker-svc", LogLevel.ERROR, "Job processing failed");
        ingestEvent(TENANT_A, "worker-svc", LogLevel.ERROR, "Job processing failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.analysisVersion").value(1))
            .andExpect(jsonPath("$.data.evidenceCount").value(2));
    }

    // (j) Second call on the same incident increments analysisVersion to 2
    @Test
    void analyze_secondCallIncrementsVersion() throws Exception {
        ingestEvent(TENANT_A, "notification-svc", LogLevel.ERROR, "Email delivery failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.analysisVersion").value(1));

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.analysisVersion").value(2));
    }

    // (k) likelyCause contains the evidence item ID cited in evidenceRefs
    @Test
    void analyze_likelyCauseContainsEvidenceItemId() throws Exception {
        ingestEvent(TENANT_A, "db-svc", LogLevel.ERROR, "Query execution timeout");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode root         = objectMapper.readTree(body).at("/data");
        String   likelyCause  = root.at("/likelyCause").asText();
        String   evidenceRef  = root.at("/evidenceRefs/0").asText();

        assertThat(likelyCause).isNotBlank();
        assertThat(likelyCause).contains(evidenceRef);
    }

    // (l) No raw secret appears in summary or likelyCause individually
    @Test
    void analyze_noRawSecretInSummaryOrLikelyCause() throws Exception {
        String rawToken = "eyJhbGciOiJSUzI1NiJ9.hardened.secret";
        ingestEvent(TENANT_A, "gateway-svc", LogLevel.ERROR, "Auth failed: Bearer " + rawToken);
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode data        = objectMapper.readTree(body).at("/data");
        String   summary     = data.at("/summary").asText();
        String   likelyCause = data.at("/likelyCause").asText();

        assertThat(summary).doesNotContain(rawToken);
        assertThat(likelyCause).doesNotContain(rawToken);
        assertThat(summary + likelyCause).contains("[REDACTED]");

        // also verify broader unsafe-keyword check covers extended set
        String checks = data.at("/recommendedNextChecks").toString().toLowerCase();
        assertThat(checks).doesNotContain("wipe");
        assertThat(checks).doesNotContain("purge");
        assertThat(checks).doesNotContain("shutdown");
        assertThat(checks).doesNotContain("flush");
        assertThat(checks).doesNotContain("format");
    }

    // (m) GET analyze/latest returns the most recently created analysis
    @Test
    void getLatestAnalysis_returnsLatest() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Timeout on checkout");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/incidents/{id}/analyze/latest", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.analysisVersion").value(1))
            .andExpect(jsonPath("$.data.summary").isNotEmpty());
    }

    // (n) GET analyze/latest returns 404 when no analysis exists yet
    @Test
    void getLatestAnalysis_noAnalysisYet_returns404() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Timeout on checkout");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/analyze/latest", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    // --- helper ---

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
