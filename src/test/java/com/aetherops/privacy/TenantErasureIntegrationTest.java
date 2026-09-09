package com.aetherops.privacy;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

/**
 * The "test" profile's security chain (TestSecurityConfig) is permit-all and never runs
 * TenantContextFilter, so TenantContext.get() is populated here by hand — exactly what
 * the filter would have set from a caller's own JWT in production.
 */
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

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void dryRun_returnsCountsWithoutDeletingAnything() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "erasure dry-run test");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId).with(asTenant(tenantId))
                .param("dryRun", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.tenantDeleted").value(false))
            .andExpect(jsonPath("$.data.totalRowsAffected").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        assertThat(tenantRepository.existsById(tenantId)).isTrue();
        assertThat(withTenant(tenantId, () -> logEventRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> incidentRepository.count())).isEqualTo(1);
    }

    @Test
    void defaultIsDryRun_noDataDeletedWithoutExplicitFlag() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "default dry-run test");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true));

        assertThat(tenantRepository.existsById(tenantId)).isTrue();
    }

    @Test
    void realErasure_deletesAllTenantDataAndTheTenantItself() throws Exception {
        UUID tenantId = createTenant();
        ingestEvent(tenantId, "real erasure test");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId).with(asTenant(tenantId))
                .param("dryRun", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(false))
            .andExpect(jsonPath("$.data.tenantDeleted").value(true));

        assertThat(tenantRepository.existsById(tenantId)).isFalse();
        assertThat(withTenant(tenantId, () -> logEventRepository.findAll()).stream()
            .anyMatch(e -> tenantId.equals(e.getTenantId()))).isFalse();
        assertThat(withTenant(tenantId, () -> incidentRepository.findAll()).stream()
            .anyMatch(i -> tenantId.equals(i.getTenantId()))).isFalse();
    }

    @Test
    void tenantIsolation_erasingOneTenantNeverTouchesAnother() throws Exception {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        ingestEvent(tenantA, "tenant A data");
        ingestEvent(tenantB, "tenant B data");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantA).with(asTenant(tenantA))
                .param("dryRun", "false"))
            .andExpect(status().isOk());

        assertThat(tenantRepository.existsById(tenantA)).isFalse();
        assertThat(tenantRepository.existsById(tenantB)).isTrue();
        assertThat(withTenant(tenantB, () -> logEventRepository.findAll()).stream()
            .anyMatch(e -> tenantB.equals(e.getTenantId()))).isTrue();
        assertThat(withTenant(tenantB, () -> incidentRepository.findAll()).stream()
            .anyMatch(i -> tenantB.equals(i.getTenantId()))).isTrue();
    }

    @Test
    void callerFromAnotherTenant_isForbidden_andNeverErasesTargetTenant() throws Exception {
        UUID tenantA = createTenant();
        UUID tenantB = createTenant();
        ingestEvent(tenantA, "must survive — attacker is tenant B");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantA).with(asTenant(tenantB))
                .param("dryRun", "false"))
            .andExpect(status().isForbidden());

        assertThat(tenantRepository.existsById(tenantA)).isTrue();
        assertThat(withTenant(tenantA, () -> logEventRepository.findAll()).stream()
            .anyMatch(e -> tenantA.equals(e.getTenantId()))).isTrue();
    }

    @Test
    void noCallerContext_isForbidden() throws Exception {
        UUID tenantId = createTenant();

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", tenantId)
                .param("dryRun", "false"))
            .andExpect(status().isForbidden());

        assertThat(tenantRepository.existsById(tenantId)).isTrue();
    }

    @Test
    void unknownTenant_returns404ForItsOwnCaller() throws Exception {
        UUID unknownTenantId = UUID.fromString("00000000-0000-0000-0000-000000000000");

        mockMvc.perform(post("/api/v1/tenants/{id}/privacy/erase", unknownTenantId).with(asTenant(unknownTenantId))
                .param("dryRun", "false"))
            .andExpect(status().isNotFound());
    }

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Erasure Test Tenant " + UUID.randomUUID(), Plan.STARTER, 0, 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
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
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }
}
