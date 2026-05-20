package com.aetherops.service;

import com.aetherops.apikey.repository.ApiKeyRepository;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.project.repository.ProjectRepository;
import com.aetherops.service.repository.ServiceRepository;
import com.aetherops.service.service.ServiceRegistryService;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
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
class ServiceIntegrationTest {

    @Autowired MockMvc               mockMvc;
    @Autowired ObjectMapper          objectMapper;
    @Autowired ServiceRegistryService serviceRegistryService;

    @Autowired TenantRepository       tenantRepository;
    @Autowired ProjectRepository      projectRepository;
    @Autowired ApiKeyRepository       apiKeyRepository;
    @Autowired ServiceRepository      serviceRepository;
    @Autowired EvidenceItemRepository evidenceItemRepository;
    @Autowired IncidentRepository     incidentRepository;
    @Autowired LogEventRepository     logEventRepository;

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        serviceRepository.deleteAll();
        projectRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // (1) Manually created service appears in the project's service list.
    @Test
    void createService_appearsInList() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Test Project");

        mockMvc.perform(post("/api/v1/projects/{id}/services", projectId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"payment-svc\", \"environment\": \"PRODUCTION\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("payment-svc"))
            .andExpect(jsonPath("$.data.environment").value("PRODUCTION"))
            .andExpect(jsonPath("$.data.projectId").value(projectId.toString()));

        mockMvc.perform(get("/api/v1/projects/{id}/services", projectId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("payment-svc"));
    }

    // (2) An invalid environment value is rejected with 422.
    @Test
    void createService_invalidEnvironment_returns422() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Test Project");

        mockMvc.perform(post("/api/v1/projects/{id}/services", projectId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"payment-svc\", \"environment\": \"invalid\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.details.environment").isNotEmpty());
    }

    // (3) Ingesting via API key auto-registers the service under the key's project.
    @Test
    void ingestWithApiKey_autoRegistersService() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Auto Register Test");
        String plaintext = createApiKey(projectId, tenantId);

        LogEventRequest req = new LogEventRequest();
        req.setServiceId("auto-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage("auto registration test");
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .header("X-Api-Key", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/projects/{id}/services", projectId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("auto-svc"));
    }

    // (4) Ingesting without a project (body tenantId, no API key) does not register any service.
    @Test
    void ingestWithoutProject_doesNotRegisterService() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "No Register Test");

        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId("no-proj-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage("no project ingestion");
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/projects/{id}/services", projectId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(0));
    }

    // (5) Calling findOrRegister twice with the same identifiers produces exactly one Service row.
    @Test
    void concurrentAutoRegistration_deduplicates() {
        UUID tenantId  = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID projectId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        serviceRegistryService.findOrRegister(tenantId, projectId, "dedup-svc", "PRODUCTION");
        serviceRegistryService.findOrRegister(tenantId, projectId, "dedup-svc", "PRODUCTION");

        assertThat(serviceRepository.findByTenantIdAndProjectId(tenantId, projectId)).hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID createTenant() throws Exception {
        var request = new CreateTenantRequest("Test Tenant", Plan.STARTER, 0, 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private UUID createProject(UUID tenantId, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateProjectRequest(tenantId, name))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private String createApiKey(UUID projectId, UUID tenantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/plaintext").asText();
    }
}
