package com.aetherops.auth;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.auth.model.Role;
import com.aetherops.branding.BrandingRequest;
import com.aetherops.incident.model.CreateIncidentRequest;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 1 Step B regression coverage: proves the @PreAuthorize role gates added to
 * controllers are actually enforced, one test per tier boundary. The very first test
 * below was written and run against the controllers BEFORE MethodSecurityConfig's
 * @EnableMethodSecurity existed — it got 201, not 403, confirming @PreAuthorize is
 * inert without it. It was re-run after adding the annotation and now gets 403,
 * confirming method security is genuinely switched on rather than assumed.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleEnforcementTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ── OWNER-only tier (e.g. API key management) ───────────────────────────

    @Test
    void ownerOnlyEndpoint_memberCaller_returns403() throws Exception {
        UUID tenantId = createTenant();
        UUID projectId = createProject(tenantId);

        mockMvc.perform(post("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString())
                .with(asTenant(tenantId, UUID.randomUUID(), Role.MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void ownerOnlyEndpoint_ownerCaller_succeeds() throws Exception {
        UUID tenantId = createTenant();
        UUID projectId = createProject(tenantId);

        mockMvc.perform(post("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString())
                .with(asTenant(tenantId, UUID.randomUUID(), Role.OWNER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated());
    }

    // ── OWNER-or-ADMIN tier (e.g. branding) ──────────────────────────────────

    @Test
    void ownerOrAdminEndpoint_memberCaller_returns403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var request = new BrandingRequest("Acme", null, "#112233", "#445566");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/branding", tenantId)
                .with(asTenant(tenantId, UUID.randomUUID(), Role.MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void ownerOrAdminEndpoint_adminCaller_succeeds() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var request = new BrandingRequest("Acme", null, "#112233", "#445566");

        mockMvc.perform(put("/api/v1/tenants/{tenantId}/branding", tenantId)
                .with(asTenant(tenantId, UUID.randomUUID(), Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk());
    }

    // ── Any-authenticated-member tier (e.g. incident creation) ───────────────
    // There's no role below MEMBER to prove a 403 against — this proves instead that
    // the tier was correctly left ungated (beyond authentication), not over-restricted.

    @Test
    void anyMemberEndpoint_memberCaller_succeeds() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var request = new CreateIncidentRequest(
            tenantId, null, "Test incident", null, IncidentSeverity.HIGH, "svc", "prod", null, null);

        mockMvc.perform(post("/api/v1/incidents")
                .with(asTenant(tenantId, UUID.randomUUID(), Role.MEMBER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Test Tenant", 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private UUID createProject(UUID tenantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateProjectRequest(tenantId, "Test Project")))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }
}
