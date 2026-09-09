package com.aetherops.statuspage;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.auth.model.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Regression test, real Postgres with RLS enforced (see STEP_REPORT.md): a second tenant
 * claiming a slug already owned by another tenant used to throw an uncaught
 * DataIntegrityViolationException -> 500, because the pre-check (existsBySlug) ran under the
 * caller's own RLS scope and could never see the other tenant's row. This exercises the real
 * database constraint end-to-end (a Mockito-mocked repository can't prove anything about RLS or
 * unique-index behavior at all), asserting the second tenant now gets a clean 409, not a 500.
 */
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StatusPageIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void configure_secondTenantClaimsSlugOwnedByAnotherTenant_getsCleanConflict_notServerError() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID ownerA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        String slug = "shared-slug-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/status-page", tenantA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Tenant A Status", "slug", slug)))
                .with(asTenant(tenantA, ownerA, Role.OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.slug").value(slug));

        // Before the fix: this threw an uncaught DataIntegrityViolationException (500) because
        // existsBySlug(slug), scoped by tenant B's own RLS context, could never see tenant A's
        // row for the same slug.
        mockMvc.perform(post("/api/v1/tenants/{tenantId}/status-page", tenantB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Tenant B Status", "slug", slug)))
                .with(asTenant(tenantB, ownerB, Role.OWNER)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("Slug '" + slug + "' is already taken"));
    }

    @Test
    void configure_ownExistingSlugUnchanged_stillSucceeds() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String slug = "own-slug-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/tenants/{tenantId}/status-page", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "First Name", "slug", slug)))
                .with(asTenant(tenantId, ownerId, Role.OWNER)))
            .andExpect(status().isOk());

        // Reconfiguring with the SAME slug the tenant already owns must not be mistaken for a
        // collision -- it's an update of their own row.
        mockMvc.perform(post("/api/v1/tenants/{tenantId}/status-page", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", "Renamed", "slug", slug)))
                .with(asTenant(tenantId, ownerId, Role.OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Renamed"))
            .andExpect(jsonPath("$.data.slug").value(slug));
    }
}
