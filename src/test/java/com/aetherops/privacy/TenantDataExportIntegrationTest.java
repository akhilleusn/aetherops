package com.aetherops.privacy;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantDataExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;
    @Autowired IncidentRepository incidentRepository;
    @Autowired LogEventRepository logEventRepository;

    @BeforeEach
    void setUp() {
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    @Test
    void export_includesTenantScopedRowsAcrossTables() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "export test incident");

        JsonNode body = objectMapper.readTree(
            mockMvc.perform(get("/api/v1/tenants/{id}/privacy/export", tenantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()))
                .andReturn().getResponse().getContentAsString());

        JsonNode tables = body.at("/data/tables");
        assertThat(tables.has("log_events")).isTrue();
        assertThat(tables.has("incidents")).isTrue();
        assertThat(tables.at("/log_events").size()).isEqualTo(1);
        assertThat(tables.at("/incidents").size()).isEqualTo(1);
        assertThat(tables.at("/log_events/0/message").asText()).contains("export test incident");
    }

    @Test
    void export_tenantIsolation_neverIncludesOtherTenantData() throws Exception {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        ingestEvent(tenantA, "tenant A only");
        ingestEvent(tenantB, "tenant B only");

        JsonNode body = objectMapper.readTree(
            mockMvc.perform(get("/api/v1/tenants/{id}/privacy/export", tenantA))
                .andReturn().getResponse().getContentAsString());

        JsonNode logEvents = body.at("/data/tables/log_events");
        assertThat(logEvents.size()).isEqualTo(1);
        assertThat(logEvents.get(0).get("message").asText()).contains("tenant A only");
    }

    @Test
    void export_unknownTenant_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}/privacy/export",
                "00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound());
    }

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Export Test Tenant " + UUID.randomUUID(), Plan.STARTER, 0, 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private void ingestEvent(UUID tenantId, String message) throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId("export-test-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }
}
