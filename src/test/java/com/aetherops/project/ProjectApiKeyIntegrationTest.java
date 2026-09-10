package com.aetherops.project;

import com.aetherops.apikey.repository.ApiKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.project.repository.ProjectRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class ProjectApiKeyIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;

    @Autowired TenantRepository    tenantRepository;
    @Autowired ProjectRepository   projectRepository;
    @Autowired ApiKeyRepository    apiKeyRepository;
    @Autowired LogEventRepository  logEventRepository;
    @Autowired IncidentRepository  incidentRepository;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        projectRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // (1) Create a project and verify it appears in the tenant's project list.
    @Test
    void createProject_appearsInList() throws Exception {
        UUID tenantId = createTenant();

        mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateProjectRequest(tenantId, "My Service")))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.name").value("My Service"))
            .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.data.id").isNotEmpty());

        mockMvc.perform(get("/api/v1/projects").param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("My Service"));
    }

    // (2) Creating a key returns plaintext exactly once; the list endpoint exposes only the prefix.
    @Test
    void createApiKey_returnsPlaintextOnce() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Demo Service");

        String response = mockMvc.perform(post("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.plaintext").isNotEmpty())
            .andExpect(jsonPath("$.data.keyPrefix").isNotEmpty())
            .andReturn().getResponse().getContentAsString();

        String plaintext = objectMapper.readTree(response).at("/data/plaintext").asText();
        assertThat(plaintext).startsWith("ak_");
        assertThat(plaintext).hasSizeGreaterThan(12);

        mockMvc.perform(get("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].keyPrefix").isNotEmpty())
            .andExpect(jsonPath("$.data[0].plaintext").doesNotExist())
            .andExpect(jsonPath("$.data[0].keyHash").doesNotExist());
    }

    // (3) A valid API key in the X-Api-Key header authenticates ingestion without tenantId in body.
    @Test
    void ingestWithValidKey_createsEventUnderCorrectTenant() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Ingest Service");
        String plaintext = createApiKey(projectId, tenantId);

        LogEventRequest req = new LogEventRequest();
        req.setServiceId("ingest-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage("connection refused to database");
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .header("X-Api-Key", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.data.projectId").value(projectId.toString()));
    }

    // (4) An unknown key returns 401 with the standard ErrorResponse shape.
    @Test
    void ingestWithInvalidKey_returns401() throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setServiceId("svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage("test message");
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .header("X-Api-Key", "ak_thiskeyisnotvalid000000000000000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("Unauthorized"))
            .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // (5) A revoked key is rejected with 401.
    @Test
    void revokeKey_thenIngest_returns401() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Revoke Test");
        String plaintext = createApiKey(projectId, tenantId);
        UUID keyId = getFirstKeyId(projectId, tenantId);

        mockMvc.perform(delete("/api/v1/projects/{pid}/keys/{kid}", projectId, keyId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isNoContent());

        LogEventRequest req = new LogEventRequest();
        req.setServiceId("svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage("post-revoke attempt");
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .header("X-Api-Key", plaintext)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnauthorized());
    }

    // (6) The key list response body never contains keyHash — only keyPrefix.
    @Test
    void keyListResponse_neverExposesHash() throws Exception {
        UUID tenantId  = createTenant();
        UUID projectId = createProject(tenantId, "Hash Guard");
        createApiKey(projectId, tenantId);

        String body = mockMvc.perform(get("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("keyHash");
        assertThat(body).contains("keyPrefix");
        assertThat(body).contains("ak_");
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

    private UUID createProject(UUID tenantId, String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/projects")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateProjectRequest(tenantId, name)))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private String createApiKey(UUID projectId, UUID tenantId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/plaintext").asText();
    }

    private UUID getFirstKeyId(UUID projectId, UUID tenantId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/projects/{id}/keys", projectId)
                .param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/0/id").asText());
    }
}
