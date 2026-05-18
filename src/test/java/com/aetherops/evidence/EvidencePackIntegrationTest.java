package com.aetherops.evidence;

import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EvidencePackIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IncidentRepository incidentRepository;
    @Autowired LogEventRepository logEventRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @BeforeEach
    void setUp() {
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
    }

    // (a) ERROR log creates incident AND one evidence item
    @Test
    void errorLog_createsIncidentAndOneEvidenceItem() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");

        assertThat(incidentRepository.findAll()).hasSize(1);
        assertThat(evidenceItemRepository.findAll()).hasSize(1);

        var item = evidenceItemRepository.findAll().get(0);
        var incident = incidentRepository.findAll().get(0);
        assertThat(item.getIncidentId()).isEqualTo(incident.getId());
        assertThat(item.getTenantId()).isEqualTo(TENANT_A);
    }

    // (b) Repeated same ERROR adds a second evidence item; incident stays one
    @Test
    void repeatedError_addsMoreEvidenceWithoutDuplicatingIncident() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");

        assertThat(incidentRepository.findAll()).hasSize(1);
        assertThat(evidenceItemRepository.findAll()).hasSize(2);

        var incident = incidentRepository.findAll().get(0);
        assertThat(incident.getEventCount()).isEqualTo(2);
    }

    // (c) Evidence pack response contains logEventId, summary, level
    @Test
    void evidencePack_containsLogEventIdSummaryAndLevel() throws Exception {
        ingestEvent(TENANT_A, "order-svc", LogLevel.ERROR, "Order processing failed");

        UUID incidentId = incidentRepository.findAll().get(0).getId();
        UUID logEventId = logEventRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.totalEvidenceCount").value(1))
            .andExpect(jsonPath("$.data.evidence[0].logEventId").value(logEventId.toString()))
            .andExpect(jsonPath("$.data.evidence[0].level").value("ERROR"))
            .andExpect(jsonPath("$.data.evidence[0].summary").value("Order processing failed"))
            .andExpect(jsonPath("$.data.evidence[0].type").value("LOG_EVENT"));
    }

    // (d) Tenant A cannot access Tenant B's evidence pack — returns 404
    @Test
    void tenantIsolation_wrongTenantReturns404() throws Exception {
        ingestEvent(TENANT_B, "auth-svc", LogLevel.ERROR, "Unauthorised access");

        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isNotFound());
    }

    // (e) FATAL log → CRITICAL incident → evidence item with level FATAL
    @Test
    void fatalLog_criticalIncidentHasFatalEvidenceItem() throws Exception {
        ingestEvent(TENANT_A, "auth-svc", LogLevel.FATAL, "JVM out of memory");

        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.severity").value("CRITICAL"))
            .andExpect(jsonPath("$.data.evidence[0].level").value("FATAL"));
    }

    // (f) Evidence summary uses masked message, not the raw secret
    @Test
    void evidenceSummary_usesMaskedMessageNotRawSecret() throws Exception {
        String rawMessage = "Auth failed: Bearer eyJhbGciOiJSUzI1NiJ9.payload.sig";
        ingestEvent(TENANT_A, "api-gateway", LogLevel.ERROR, rawMessage);

        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.evidence[0].summary").value(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("eyJhbGciOiJSUzI1NiJ9"))))
            .andExpect(jsonPath("$.data.evidence[0].summary").value(
                org.hamcrest.Matchers.containsString("[REDACTED]")));
    }

    // (g) Evidence pack returns full incident context
    @Test
    void evidencePack_returnsFullIncidentContext() throws Exception {
        ingestEvent(TENANT_A, "checkout-svc", LogLevel.ERROR, "Payment gateway timeout");

        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()))
            .andExpect(jsonPath("$.data.serviceId").value("checkout-svc"))
            .andExpect(jsonPath("$.data.title").isNotEmpty())
            .andExpect(jsonPath("$.data.severity").value("MEDIUM"))
            .andExpect(jsonPath("$.data.status").value("OPEN"))
            .andExpect(jsonPath("$.data.firstSeen").isNotEmpty())
            .andExpect(jsonPath("$.data.lastSeen").isNotEmpty());
    }

    // (h) totalEvidenceCount in API response matches number of ingested events
    @Test
    void evidencePack_totalCountMatchesNumberOfIngestedEvents() throws Exception {
        ingestEvent(TENANT_A, "inventory-svc", LogLevel.ERROR, "Stock check failed");
        ingestEvent(TENANT_A, "inventory-svc", LogLevel.ERROR, "Stock check failed");
        ingestEvent(TENANT_A, "inventory-svc", LogLevel.ERROR, "Stock check failed");

        UUID incidentId = incidentRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalEvidenceCount").value(3))
            .andExpect(jsonPath("$.data.evidence.length()").value(3));
    }

    // (i) Evidence items are ordered newest first (deterministic timestamps)
    @Test
    void evidenceItems_areOrderedNewestFirst() throws Exception {
        Instant older = Instant.now().minusSeconds(120);
        Instant newer = Instant.now();

        ingestEventAt(TENANT_A, "shipping-svc", LogLevel.ERROR, "Delivery failed", older);
        ingestEventAt(TENANT_A, "shipping-svc", LogLevel.ERROR, "Delivery failed", newer);

        UUID incidentId = incidentRepository.findAll().get(0).getId();

        String body = mockMvc.perform(get("/api/v1/incidents/{id}/evidence-pack", incidentId)
                .param("tenantId", TENANT_A.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.evidence.length()").value(2))
            .andReturn().getResponse().getContentAsString();

        Instant first  = Instant.parse(objectMapper.readTree(body).at("/data/evidence/0/timestamp").asText());
        Instant second = Instant.parse(objectMapper.readTree(body).at("/data/evidence/1/timestamp").asText());
        assertThat(first).isAfter(second);
    }

    // --- helper ---

    private void ingestEvent(UUID tenantId, String serviceId, LogLevel level, String message)
        throws Exception {
        ingestEventAt(tenantId, serviceId, level, message, Instant.now());
    }

    private void ingestEventAt(UUID tenantId, String serviceId, LogLevel level, String message,
                                Instant occurredAt) throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId(serviceId);
        req.setLevel(level);
        req.setMessage(message);
        req.setOccurredAt(occurredAt);

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }
}
