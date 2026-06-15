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
    void createTenant_validRequest_returns201WithBody() throws Exception {
        var request = new CreateTenantRequest("Acme Corp", Plan.STARTER, 5000, 30);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.name").value("Acme Corp"))
            .andExpect(jsonPath("$.data.plan").value("STARTER"))
            .andExpect(jsonPath("$.data.aiMonthlyBudgetCents").value(5000))
            .andExpect(jsonPath("$.data.retentionDays").value(30));
    }

    @Test
    void createTenant_nameTooShort_returns422WithFieldError() throws Exception {
        var request = new CreateTenantRequest("A", Plan.FREE, 0, 7);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.details.name").isNotEmpty())
            .andExpect(jsonPath("$.path").value("/api/v1/tenants"));
    }

    @Test
    void createTenant_negativeBudget_returns422() throws Exception {
        var request = new CreateTenantRequest("Valid Name", Plan.FREE, -1, 7);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.aiMonthlyBudgetCents").isNotEmpty());
    }

    @Test
    void createTenant_retentionDaysOutOfRange_returns422() throws Exception {
        var request = new CreateTenantRequest("Valid Name", Plan.FREE, 0, 366);

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.retentionDays").isNotEmpty());
    }

    @Test
    void listTenants_returnsPagedResults() throws Exception {
        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTenantRequest("Alpha", Plan.STARTER, 0, 30))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTenantRequest("Beta", Plan.TEAM, 10000, 90))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/tenants"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.page.totalElements").value(2));
    }

    @Test
    void getTenantById_existingTenant_returnsCorrectData() throws Exception {
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTenantRequest("Gamma Inc", Plan.GROWTH, 20000, 180))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String id = objectMapper.readTree(body).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/tenants/" + id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Gamma Inc"))
            .andExpect(jsonPath("$.data.plan").value("GROWTH"))
            .andExpect(jsonPath("$.data.retentionDays").value(180));
    }

    @Test
    void getTenantById_notFound_returns404WithErrorShape() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").value("/api/v1/tenants/00000000-0000-0000-0000-000000000000"))
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
