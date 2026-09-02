package com.aetherops.incident;

import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
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
class IncidentGroupingIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
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
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
    }

    @Test
    void firstErrorLog_createsOpenIncidentWithMediumSeverity() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");

        var incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);

        var incident = incidents.get(0);
        assertThat(incident.getTenantId()).isEqualTo(TENANT_A);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.MEDIUM);
        assertThat(incident.getEventCount()).isEqualTo(1);
        assertThat(incident.getServiceId()).isEqualTo("payment-svc");
        assertThat(incident.getFirstSeenAt()).isNotNull();
        assertThat(incident.getLastSeenAt()).isNotNull();
    }

    @Test
    void repeatedSameError_incrementsEventCountNotDuplicateIncident() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");

        var incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        assertThat(incidents.get(0).getEventCount()).isEqualTo(2);
    }

    @Test
    void differentService_createsSeperateIncident() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");
        ingestEvent(TENANT_A, "order-svc",   LogLevel.ERROR, "Connection refused");

        assertThat(incidentRepository.findAll()).hasSize(2);
    }

    @Test
    void differentTenant_createsSeperateIncident() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");
        ingestEvent(TENANT_B, "payment-svc", LogLevel.ERROR, "Connection refused");

        var incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(2);
        assertThat(incidents).extracting("tenantId")
            .containsExactlyInAnyOrder(TENANT_A, TENANT_B);
    }

    @Test
    void errorRepeatedThreeTimes_escalatesToHighSeverity() throws Exception {
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");
        ingestEvent(TENANT_A, "payment-svc", LogLevel.ERROR, "Connection refused");

        var incident = incidentRepository.findAll().get(0);
        assertThat(incident.getEventCount()).isEqualTo(3);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.HIGH);
    }

    @Test
    void fatalLog_createsCriticalIncident() throws Exception {
        ingestEvent(TENANT_A, "auth-svc", LogLevel.FATAL, "Out of memory");

        var incident = incidentRepository.findAll().get(0);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void warnLog_createsLowSeverityIncident() throws Exception {
        ingestEvent(TENANT_A, "cache-svc", LogLevel.WARN, "Cache miss rate high");

        var incident = incidentRepository.findAll().get(0);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.LOW);
    }

    @Test
    void infoLog_doesNotCreateIncident() throws Exception {
        ingestEvent(TENANT_A, "order-svc", LogLevel.INFO, "Order processed");

        assertThat(incidentRepository.findAll()).isEmpty();
    }

    @Test
    void listIncidents_byTenantId_onlyReturnsTenantIncidents() throws Exception {
        ingestEvent(TENANT_A, "svc-a", LogLevel.ERROR, "error A");
        ingestEvent(TENANT_B, "svc-b", LogLevel.ERROR, "error B");

        mockMvc.perform(get("/api/v1/incidents").param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].tenantId").value(TENANT_A.toString()));
    }

    @Test
    void listIncidents_filterBySeverity_returnsFilteredResults() throws Exception {
        ingestEvent(TENANT_A, "svc-a", LogLevel.FATAL, "fatal error");   // CRITICAL
        ingestEvent(TENANT_A, "svc-b", LogLevel.WARN,  "slow response"); // LOW

        mockMvc.perform(get("/api/v1/incidents")
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A))
                .param("severity", "CRITICAL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].severity").value("CRITICAL"));
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
