package com.aetherops.incident;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.incident.model.CreateIncidentRequest;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.UpdateIncidentRequest;
import com.aetherops.incident.repository.IncidentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 Step D: full-stack proof (real HTTP, real Postgres, real RLS) that a member of
 * tenant A requesting tenant B's incident by ID gets 404, never 200 and never a
 * distinguishable 403 — for every IncidentController endpoint identified in D0(a), plus
 * the downstream IncidentCommandController.resolve path that shared the same unguarded
 * IncidentService.update method.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IncidentTenantIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IncidentRepository incidentRepository;

    @BeforeEach
    void setUp() {
        incidentRepository.deleteAll();
    }

    private UUID createIncident(UUID tenantId) throws Exception {
        var request = new CreateIncidentRequest(
            tenantId, null, "Isolation test incident", null, IncidentSeverity.LOW, "svc", "test", null, null);
        String body = mockMvc.perform(post("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    // ── GET /api/v1/incidents/{id} ──────────────────────────────────────────

    @Test
    void getById_ownIncident_returns200() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = createIncident(tenantId);

        mockMvc.perform(get("/api/v1/incidents/{id}", incidentId)
                .param("tenantId", tenantId.toString())
                .with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(incidentId.toString()));
    }

    @Test
    void getById_otherTenantsIncident_returns404_notForbidden() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID incidentBId = createIncident(tenantB);

        mockMvc.perform(get("/api/v1/incidents/{id}", incidentBId)
                .param("tenantId", tenantA.toString())
                .with(asTenant(tenantA)))
            .andExpect(status().isNotFound());
    }

    // ── PATCH /api/v1/incidents/{id} ────────────────────────────────────────

    @Test
    void update_ownIncident_returns200() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = createIncident(tenantId);
        var request = new UpdateIncidentRequest();
        request.setTitle("Updated by owner");

        mockMvc.perform(patch("/api/v1/incidents/{id}", incidentId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("Updated by owner"));
    }

    @Test
    void update_otherTenantsIncident_returns404_notForbidden() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID incidentBId = createIncident(tenantB);
        var request = new UpdateIncidentRequest();
        request.setTitle("Attacker's edit");

        mockMvc.perform(patch("/api/v1/incidents/{id}", incidentBId)
                .param("tenantId", tenantA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(tenantA)))
            .andExpect(status().isNotFound());
    }

    // ── DELETE /api/v1/incidents/{id} ───────────────────────────────────────

    @Test
    void delete_ownIncident_returns204() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = createIncident(tenantId);

        mockMvc.perform(delete("/api/v1/incidents/{id}", incidentId)
                .param("tenantId", tenantId.toString())
                .with(asTenant(tenantId)))
            .andExpect(status().isNoContent());
    }

    @Test
    void delete_otherTenantsIncident_returns404_notForbidden() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID incidentBId = createIncident(tenantB);

        mockMvc.perform(delete("/api/v1/incidents/{id}", incidentBId)
                .param("tenantId", tenantA.toString())
                .with(asTenant(tenantA)))
            .andExpect(status().isNotFound());

        // And prove it wasn't silently soft-deleted despite the 404.
        mockMvc.perform(get("/api/v1/incidents/{id}", incidentBId)
                .param("tenantId", tenantB.toString())
                .with(asTenant(tenantB)))
            .andExpect(status().isOk());
    }

    // ── POST /api/v1/incidents — finding 5 (see STEP_REPORT.md): IncidentController.create()
    // was missing the TenantContext.requireOwn() its CQRS sibling already had ──────────────

    @Test
    void create_forgedOtherTenantsTenantId_returns403_notServerError() throws Exception {
        UUID callerTenantId = UUID.randomUUID();
        UUID victimTenantId = UUID.randomUUID();
        var request = new CreateIncidentRequest(
            victimTenantId, null, "Forged incident", null, IncidentSeverity.LOW, "svc", "test", null, null);

        mockMvc.perform(post("/api/v1/incidents")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(callerTenantId)))
            .andExpect(status().isForbidden());

        // And prove nothing was created under either tenant despite the RLS-driven collision
        // this used to 500 on (see STEP_REPORT.md's trace of the old failure mode).
        assertThat(incidentRepository.findAll()).isEmpty();
    }

    // ── GET /api/v2/incidents — finding 5 sweep (see STEP_REPORT.md): found during the
    // controller-wide sweep, not previously known — IncidentControllerV2.list() had the same
    // gap as v1's create() ──────────────────────────────────────────────────────────────────

    @Test
    void v2List_ownTenantId_returns200() throws Exception {
        UUID tenantId = UUID.randomUUID();
        createIncident(tenantId);

        mockMvc.perform(get("/api/v2/incidents")
                .param("tenantId", tenantId.toString())
                .with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.content", org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void v2List_forgedOtherTenantsTenantId_returns403_notServerError() throws Exception {
        UUID callerTenantId = UUID.randomUUID();
        UUID victimTenantId = UUID.randomUUID();
        createIncident(victimTenantId);

        mockMvc.perform(get("/api/v2/incidents")
                .param("tenantId", victimTenantId.toString())
                .with(asTenant(callerTenantId)))
            .andExpect(status().isForbidden());
    }

    // ── POST /api/v1/commands/incidents/{id}/resolve — shared IncidentService.update ──

    @Test
    void resolveCommand_ownIncident_returns200() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = createIncident(tenantId);

        mockMvc.perform(post("/api/v1/commands/incidents/{id}/resolve", incidentId)
                .param("tenantId", tenantId.toString())
                .with(asTenant(tenantId)))
            .andExpect(status().isOk());
    }

    @Test
    void resolveCommand_otherTenantsIncident_returns404_notForbidden() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID incidentBId = createIncident(tenantB);

        // requireOwn(tenantA) passes here — tenantA IS the caller's own real tenant. The
        // attack is supplying someone else's incident id alongside your own valid tenant.
        mockMvc.perform(post("/api/v1/commands/incidents/{id}/resolve", incidentBId)
                .param("tenantId", tenantA.toString())
                .with(asTenant(tenantA)))
            .andExpect(status().isNotFound());
    }
}
