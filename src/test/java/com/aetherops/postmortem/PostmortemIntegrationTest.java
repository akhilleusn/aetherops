package com.aetherops.postmortem;

import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostmortemIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired PostmortemDraftRepository postmortemDraftRepository;
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
        postmortemDraftRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
    }

    // (a) POST /postmortem generates and returns a structured draft
    @Test
    void generatePostmortem_createsAndReturnsDraft() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Database connection pool exhausted");
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()))
            .andExpect(jsonPath("$.data.draftVersion").value(1))
            .andExpect(jsonPath("$.data.generatorVersion").value("mock-v1"))
            .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    // (b) Draft has all required sections populated
    @Test
    void generatePostmortem_allRequiredSectionsPresent() throws Exception {
        ingestEvent(TENANT_A, "order-svc", LogLevel.ERROR, "Order processing failed");
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.incidentSummary").isNotEmpty())
            .andExpect(jsonPath("$.data.timeline").isArray())
            .andExpect(jsonPath("$.data.likelyCause").isNotEmpty())
            .andExpect(jsonPath("$.data.impact").isNotEmpty())
            .andExpect(jsonPath("$.data.dataGaps").isArray())
            .andExpect(jsonPath("$.data.followUpActions").isArray());
    }

    // (c) Timeline contains evidence-grounded entries with real evidence item IDs
    @Test
    void generatePostmortem_timelineContainsRealEvidenceRefs() throws Exception {
        ingestEvent(TENANT_A, "inventory-svc", LogLevel.ERROR, "Stock check failed");
        UUID incidentId   = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());
        String evidenceId = withTenant(TENANT_A, () -> evidenceItemRepository.findAll().get(0).getId().toString());

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode timeline = objectMapper.readTree(body).at("/data/timeline");
        assertThat(timeline.isArray()).isTrue();

        boolean foundEvidenceRef = false;
        for (JsonNode entry : timeline) {
            String ref = entry.at("/evidenceRef").asText("");
            if (evidenceId.equals(ref)) {
                foundEvidenceRef = true;
                break;
            }
        }
        assertThat(foundEvidenceRef)
            .as("Timeline must contain at least one entry with the real evidence item ID " + evidenceId)
            .isTrue();
    }

    // (d) No destructive actions in followUpActions
    @Test
    void generatePostmortem_noDestructiveFollowUpActions() throws Exception {
        ingestEvent(TENANT_A, "billing-svc", LogLevel.ERROR, "Charge processing failed");
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String actions = objectMapper.readTree(body).at("/data/followUpActions").toString().toLowerCase();
        assertThat(actions).doesNotContain("delete");
        assertThat(actions).doesNotContain("drop");
        assertThat(actions).doesNotContain("truncate");
        assertThat(actions).doesNotContain("rm -rf");
        assertThat(actions).doesNotContain("wipe");
        assertThat(actions).doesNotContain("shutdown");
    }

    // (e) No raw secret or token in any section
    @Test
    void generatePostmortem_noRawSecretInAnySection() throws Exception {
        String rawToken = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        ingestEvent(TENANT_A, "api-gateway", LogLevel.ERROR, "Auth failed: Bearer " + rawToken);
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(rawToken);
    }

    // (f) Repeated POST calls each create a new draft version (append-only)
    @Test
    void generatePostmortem_repeatedCallCreatesNewVersionEachTime() throws Exception {
        ingestEvent(TENANT_A, "search-svc", LogLevel.ERROR, "Index query timeout");
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        String body1 = mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draftVersion").value(1))
            .andReturn().getResponse().getContentAsString();

        String body2 = mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draftVersion").value(2))
            .andReturn().getResponse().getContentAsString();

        String id1 = objectMapper.readTree(body1).at("/data/id").asText();
        String id2 = objectMapper.readTree(body2).at("/data/id").asText();

        assertThat(id1).isNotEqualTo(id2);
        assertThat(withTenant(TENANT_A, () -> postmortemDraftRepository.count())).isEqualTo(2);
    }

    // (g) Tenant mismatch returns 404
    @Test
    void generatePostmortem_tenantMismatch_returns404() throws Exception {
        ingestEvent(TENANT_B, "auth-svc", LogLevel.ERROR, "Token validation failed");
        UUID incidentId = withTenant(TENANT_B, () -> incidentRepository.findAll().get(0).getId());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isNotFound());
    }

    // (h) GET /postmortem/latest returns the most recently generated draft
    @Test
    void getLatestPostmortem_returnsLatestAfterTwoGenerations() throws Exception {
        ingestEvent(TENANT_A, "cache-svc", LogLevel.ERROR, "Cache miss");
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/incidents/{id}/postmortem/latest", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.draftVersion").value(2))
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()));
    }

    // (i) GET /postmortem/latest returns 404 when no draft has been generated yet
    @Test
    void getLatestPostmortem_noDraftExists_returns404() throws Exception {
        ingestEvent(TENANT_A, "worker-svc", LogLevel.ERROR, "Job processing failed");
        UUID incidentId = withTenant(TENANT_A, () -> incidentRepository.findAll().get(0).getId());

        mockMvc.perform(get("/api/v1/incidents/{id}/postmortem/latest", incidentId)
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isNotFound());
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
