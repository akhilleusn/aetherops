package com.aetherops.privacy;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantErasureIntegrationTest extends AbstractIntegrationTest {

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
    void dryRun_returnsCountsWithoutDeletingAnything() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "erasure dry-run test");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId)
                .param("dryRun", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.tenantDeleted").value(false))
            .andExpect(jsonPath("$.data.totalRowsAffected").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        assertThat(tenantRepository.existsById(tenantId)).isTrue();
        assertThat(logEventRepository.count()).isEqualTo(1);
        assertThat(incidentRepository.count()).isEqualTo(1);
    }

    @Test
    void defaultIsDryRun_noDataDeletedWithoutExplicitFlag() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "default dry-run test");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true));

        assertThat(tenantRepository.existsById(tenantId)).isTrue();
    }

    @Test
    void realErasure_deletesAllTenantDataAndTheTenantItself() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "real erasure test");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId)
                .param("dryRun", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(false))
            .andExpect(jsonPath("$.data.tenantDeleted").value(true));

        assertThat(tenantRepository.existsById(tenantId)).isFalse();
        assertThat(logEventRepository.findAll().stream()
            .anyMatch(e -> tenantId.equals(e.getTenantId()))).isFalse();
        assertThat(incidentRepository.findAll().stream()
            .anyMatch(i -> tenantId.equals(i.getTenantId()))).isFalse();
    }

    @Test
    void tenantIsolation_erasingOneTenantNeverTouchesAnother() throws Exception {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        ingestEvent(tenantA, "tenant A data");
        ingestEvent(tenantB, "tenant B data");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantA)
                .param("dryRun", "false"))
            .andExpect(status().isOk());

        assertThat(tenantRepository.existsById(tenantA)).isFalse();
        assertThat(tenantRepository.existsById(tenantB)).isTrue();
        assertThat(logEventRepository.findAll().stream()
            .anyMatch(e -> tenantB.equals(e.getTenantId()))).isTrue();
        assertThat(incidentRepository.findAll().stream()
            .anyMatch(i -> tenantB.equals(i.getTenantId()))).isTrue();
    }

    @Test
    void unknownTenant_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase",
                "00000000-0000-0000-0000-000000000000")
                .param("dryRun", "false"))
            .andExpect(status().isNotFound());
    }

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Erasure Test Tenant " + UUID.randomUUID(), Plan.STARTER, 0, 30);
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
        req.setServiceId("erasure-test-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }
}
