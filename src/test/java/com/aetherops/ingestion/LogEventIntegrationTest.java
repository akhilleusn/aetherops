package com.aetherops.ingestion;

import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aetherops.tenant.TenantContext;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class LogEventIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired LogEventRepository logEventRepository;

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        logEventRepository.deleteAll();
    }

    @Test
    void ingest_validRequest_returns201WithTenantId() throws Exception {
        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildRequest(TENANT_A, null, "payment-svc", LogLevel.ERROR, "Payment failed")))
                .with(asTenant(TENANT_A)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").isNotEmpty())
            .andExpect(jsonPath("$.data.tenantId").value(TENANT_A.toString()))
            .andExpect(jsonPath("$.data.serviceId").value("payment-svc"))
            .andExpect(jsonPath("$.data.level").value("ERROR"));
    }

    @Test
    void ingest_withOptionalProjectId_persistsProjectId() throws Exception {
        UUID projectId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildRequest(TENANT_A, projectId, "order-svc", LogLevel.INFO, "Order created")))
                .with(asTenant(TENANT_A)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.projectId").value(projectId.toString()));
    }

    @Test
    void ingest_bearerTokenInMessage_isMasked() throws Exception {
        String rawMessage = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature";

        String body = mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildRequest(TENANT_A, null, "auth-svc", LogLevel.ERROR, rawMessage)))
                .with(asTenant(TENANT_A)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String message = objectMapper.readTree(body).path("data").path("message").asText();
        assertThat(message).doesNotContain("eyJhbGciOiJSUzI1NiJ9");
        assertThat(message).contains("[REDACTED]");
    }

    @Test
    void ingest_passwordInRawPayload_isMasked() throws Exception {
        LogEventRequest request = buildRequest(TENANT_A, null, "auth-svc", LogLevel.INFO, "User login attempt");
        request.setRawPayload("{\"username\":\"alice\",\"password\":\"supersecret123\"}");

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(TENANT_A)))
            .andExpect(status().isCreated());

        var saved = withTenant(TENANT_A, () -> logEventRepository.findAll().get(0));
        assertThat(saved.getRawPayload()).doesNotContain("supersecret123");
        assertThat(saved.getRawPayload()).contains("[REDACTED]");
    }

    @Test
    void ingest_missingTenantId_returns422WithFieldError() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("serviceId", "payment-svc");
        body.put("level", "ERROR");
        body.put("message", "Something failed");
        body.put("occurredAt", Instant.now().toString());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("Validation failed"))
            .andExpect(jsonPath("$.details.tenantId").isNotEmpty());
    }

    @Test
    void ingest_serviceIdTooShort_returns422() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("tenantId", TENANT_A.toString());
        body.put("serviceId", "x");
        body.put("level", "ERROR");
        body.put("message", "Something failed");
        body.put("occurredAt", Instant.now().toString());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.details.serviceId").isNotEmpty());
    }

    @Test
    void listEvents_byTenantId_onlyReturnsTenantEvents() throws Exception {
        ingestEvent(TENANT_A, "svc-a", LogLevel.ERROR, "error A1");
        ingestEvent(TENANT_A, "svc-a", LogLevel.WARN,  "warn A2");
        ingestEvent(TENANT_B, "svc-b", LogLevel.ERROR, "error B1");

        mockMvc.perform(get("/api/v1/events").param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.page.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].tenantId").value(TENANT_A.toString()));
    }

    @Test
    void listEvents_filterByServiceId_returnsFilteredResults() throws Exception {
        ingestEvent(TENANT_A, "svc-alpha", LogLevel.ERROR, "alpha error");
        ingestEvent(TENANT_A, "svc-beta",  LogLevel.ERROR, "beta error");

        mockMvc.perform(get("/api/v1/events")
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A))
                .param("serviceId", "svc-alpha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].serviceId").value("svc-alpha"));
    }

    @Test
    void listEvents_filterByLevel_returnsFilteredResults() throws Exception {
        ingestEvent(TENANT_A, "svc-a", LogLevel.ERROR, "error msg");
        ingestEvent(TENANT_A, "svc-a", LogLevel.INFO,  "info msg");
        ingestEvent(TENANT_A, "svc-a", LogLevel.WARN,  "warn msg");

        mockMvc.perform(get("/api/v1/events")
                .param("tenantId", TENANT_A.toString()).with(asTenant(TENANT_A))
                .param("level", "ERROR"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.page.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].level").value("ERROR"));
    }

    // --- helpers ---

    private LogEventRequest buildRequest(UUID tenantId, UUID projectId, String serviceId,
                                         LogLevel level, String message) {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setProjectId(projectId);
        req.setServiceId(serviceId);
        req.setLevel(level);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());
        return req;
    }

    private void ingestEvent(UUID tenantId, String serviceId, LogLevel level, String message)
        throws Exception {
        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    buildRequest(tenantId, null, serviceId, level, message)))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }
}
