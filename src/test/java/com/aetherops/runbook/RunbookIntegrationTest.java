package com.aetherops.runbook;

import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class RunbookIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RunbookSuggestionRepository runbookSuggestionRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired LogEventRepository logEventRepository;

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        runbookSuggestionRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
    }

    // (a) POST /runbook generates and returns a structured runbook suggestion
    @Test
    void generateRunbook_createsAndReturnsRunbook() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Database connection pool exhausted");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()))
            .andExpect(jsonPath("$.data.runbookVersion").value(1))
            .andExpect(jsonPath("$.data.steps").isArray())
            .andExpect(jsonPath("$.data.generatorVersion").value("mock-v1"))
            .andExpect(jsonPath("$.data.createdAt").isNotEmpty());
    }

    // (b) Generated steps cover all three phases: TRIAGE, INVESTIGATE, VERIFY
    @Test
    void generateRunbook_stepsIncludeAllThreePhases() throws Exception {
        ingestEvent(TENANT_A, "order-svc", LogLevel.ERROR, "Order processing failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String stepsJson = objectMapper.readTree(body).at("/data/steps").toString().toUpperCase();
        assertThat(stepsJson).contains("TRIAGE");
        assertThat(stepsJson).contains("INVESTIGATE");
        assertThat(stepsJson).contains("VERIFY");
    }

    // (c) INVESTIGATE steps cite real evidence item IDs from the evidence pack
    @Test
    void generateRunbook_investigateStepsCiteRealEvidenceItemIds() throws Exception {
        ingestEvent(TENANT_A, "inventory-svc", LogLevel.ERROR, "Stock check failed");
        UUID incidentId   = incidentRepository.findAll().get(0).getId();
        String evidenceId = evidenceItemRepository.findAll().get(0).getId().toString();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode steps = objectMapper.readTree(body).at("/data/steps");
        boolean foundEvidenceRef = false;
        for (JsonNode step : steps) {
            String phase = step.at("/phase").asText();
            if ("INVESTIGATE".equals(phase)) {
                String evidenceRef = step.at("/evidenceRef").asText("");
                if (evidenceId.equals(evidenceRef)) {
                    foundEvidenceRef = true;
                    break;
                }
            }
        }
        assertThat(foundEvidenceRef)
            .as("At least one INVESTIGATE step must cite the real evidence item ID " + evidenceId)
            .isTrue();
    }

    // (d) No step action or rationale contains destructive keywords
    @Test
    void generateRunbook_noStepContainsDestructiveAction() throws Exception {
        ingestEvent(TENANT_A, "billing-svc", LogLevel.ERROR, "Charge processing failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String stepsJson = objectMapper.readTree(body).at("/data/steps").toString().toLowerCase();
        assertThat(stepsJson).doesNotContain("delete");
        assertThat(stepsJson).doesNotContain("drop");
        assertThat(stepsJson).doesNotContain("truncate");
        assertThat(stepsJson).doesNotContain("rm -rf");
        assertThat(stepsJson).doesNotContain("wipe");
        assertThat(stepsJson).doesNotContain("purge all");
        assertThat(stepsJson).doesNotContain("flush all");
        assertThat(stepsJson).doesNotContain("format disk");
    }

    // (e) No raw secret or token appears in any step text
    @Test
    void generateRunbook_noRawSecretInAnyStep() throws Exception {
        String rawToken = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        ingestEvent(TENANT_A, "api-gateway", LogLevel.ERROR, "Auth failed: Bearer " + rawToken);
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String stepsJson = objectMapper.readTree(body).at("/data/steps").toString();
        assertThat(stepsJson).doesNotContain(rawToken);
    }

    // (f) Repeated POST calls each create a new runbook version (append-only)
    @Test
    void generateRunbook_repeatedCallCreatesNewVersionEachTime() throws Exception {
        ingestEvent(TENANT_A, "search-svc", LogLevel.ERROR, "Index query timeout");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body1 = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runbookVersion").value(1))
            .andReturn().getResponse().getContentAsString();

        String body2 = mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runbookVersion").value(2))
            .andReturn().getResponse().getContentAsString();

        String id1 = objectMapper.readTree(body1).at("/data/id").asText();
        String id2 = objectMapper.readTree(body2).at("/data/id").asText();

        assertThat(id1).isNotEqualTo(id2);
        assertThat(runbookSuggestionRepository.count()).isEqualTo(2);
    }

    // (g) Tenant mismatch returns 404
    @Test
    void generateRunbook_tenantMismatch_returns404() throws Exception {
        ingestEvent(TENANT_B, "auth-svc", LogLevel.ERROR, "Token validation failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isNotFound());
    }

    // (h) GET /runbook/latest returns the most recently generated runbook
    @Test
    void getLatestRunbook_returnsLatestAfterTwoGenerations() throws Exception {
        ingestEvent(TENANT_A, "cache-svc", LogLevel.ERROR, "Cache miss");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/incidents/{id}/runbook/latest", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.runbookVersion").value(2))
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()));
    }

    // (i) GET /runbook/latest returns 404 when no runbook has been generated yet
    @Test
    void getLatestRunbook_noRunbookExists_returns404() throws Exception {
        ingestEvent(TENANT_A, "worker-svc", LogLevel.ERROR, "Job processing failed");
        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/runbook/latest", incidentId)
                .param("tenantId", TENANT_A.toString()))
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
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }
}
