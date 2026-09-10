package com.aetherops.tenant;

import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenantRepository;

    @BeforeEach
    void setUp() {
        tenantRepository.deleteAll();
    }

    @Test
    void createTenant_noPlanOrBudgetSupplied_landsOnDefaultFreePlanWithZeroBudget() throws Exception {
        // Phase 2 remediation, finding 7 (see STEP_REPORT.md): plan/aiMonthlyBudgetCents are no
        // longer part of CreateTenantRequest at all -- every new tenant starts on FREE with a
        // zero AI budget, full stop.
        var request = new CreateTenantRequest("Acme Corp", 30);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.name").value("Acme Corp"))
            .andExpect(jsonPath("$.data.plan").value("FREE"))
            .andExpect(jsonPath("$.data.aiMonthlyBudgetCents").value(0))
            .andExpect(jsonPath("$.data.retentionDays").value(30));
    }

    @Test
    void createTenant_forgedPlanAndBudgetInRawJsonBody_ignored_stillLandsOnFree() throws Exception {
        // The DTO no longer HAS a plan/aiMonthlyBudgetCents field, so a typed CreateTenantRequest
        // can't even express this attack anymore -- this sends raw JSON with those fields
        // smuggled in alongside the real ones, proving Jackson's default "silently drop unknown
        // properties" behavior (relied on deliberately, see CreateTenantRequest's own comment)
        // actually holds, not just that the Java type system happens to prevent it.
        String maliciousBody = """
            {"name":"Attacker Corp","retentionDays":30,"plan":"GROWTH","aiMonthlyBudgetCents":2000000000}
            """;

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(maliciousBody)
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("Attacker Corp"))
            .andExpect(jsonPath("$.data.plan").value("FREE"))
            .andExpect(jsonPath("$.data.aiMonthlyBudgetCents").value(0));
    }

    @Test
    void createTenant_nameTooShort_returns422WithFieldError() throws Exception {
        var request = new CreateTenantRequest("A", 7);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.details.name").isNotEmpty())
            .andExpect(jsonPath("$.path").value("/api/v1/tenants"));
    }

    @Test
    void createTenant_retentionDaysOutOfRange_returns422() throws Exception {
        var request = new CreateTenantRequest("Valid Name", 366);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.retentionDays").isNotEmpty());
    }

    @Test
    void listTenants_returnsOnlyCallersOwnTenant() throws Exception {
        // Phase 1 remediation item 3: GET /tenants must never return every tenant in the
        // system — only the ones the authenticated caller belongs to, derived from the JWT.
        String alphaBody = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTenantRequest("Alpha", 30)))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID alphaId = UUID.fromString(objectMapper.readTree(alphaBody).path("data").path("id").asText());

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTenantRequest("Beta", 90)))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated());

        // Regression test (d): a member of tenant Alpha never sees tenant Beta.
        mockMvc.perform(get("/api/v1/tenants").with(asTenant(alphaId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.page.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].id").value(alphaId.toString()))
            .andExpect(jsonPath("$.data.content[0].name").value("Alpha"));
    }

    @Test
    void getTenantById_existingTenant_returnsCorrectData() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTenantRequest("Gamma Inc", 180)))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/tenants/" + id).with(asTenant(java.util.UUID.fromString(id))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Gamma Inc"))
            .andExpect(jsonPath("$.data.plan").value(Plan.FREE.name()))
            .andExpect(jsonPath("$.data.retentionDays").value(180));
    }

    @Test
    void getTenantById_notFound_returns404WithErrorShape() throws Exception {
        java.util.UUID unknownTenantId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000000");
        mockMvc.perform(get("/api/v1/tenants/00000000-0000-0000-0000-000000000000")
                .with(asTenant(unknownTenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").value("/api/v1/tenants/00000000-0000-0000-0000-000000000000"))
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
