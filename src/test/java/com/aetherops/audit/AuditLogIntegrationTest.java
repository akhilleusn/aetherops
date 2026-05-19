package com.aetherops.audit;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.repository.AuditLogRepository;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.usage.repository.TenantMonthlyUsageRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
class AuditLogIntegrationTest {

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   objectMapper;
    @Autowired AuditLogRepository             auditLogRepository;
    @Autowired TenantRepository               tenantRepository;
    @Autowired TenantMonthlyUsageRepository   usageRepository;
    @Autowired LogEventRepository             logEventRepository;
    @Autowired IncidentRepository             incidentRepository;
    @Autowired EvidenceItemRepository         evidenceItemRepository;
    @Autowired PostmortemDraftRepository      postmortemDraftRepository;
    @Autowired RunbookSuggestionRepository    runbookSuggestionRepository;

    private static final String RAW_JWT = "eyJhbGciOiJSUzI1NiJ9.audit.testtoken";

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        postmortemDraftRepository.deleteAll();
        runbookSuggestionRepository.deleteAll();
        usageRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // (a) Successful AI analysis creates ANALYSIS_GENERATED SUCCESS entry;
    //     no raw secret appears in any audit field.
    @Test
    void aiAnalysis_createsAuditLogEntry() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId,
            "Connection pool exhausted — Bearer " + RAW_JWT);

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk());

        var entries = auditLogRepository.findAll();
        assertThat(entries).hasSize(1);

        var entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.ANALYSIS_GENERATED);
        assertThat(entry.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(entry.getTenantId()).isEqualTo(tenantId);
        assertThat(entry.getResourceType()).isEqualTo("INCIDENT");
        assertThat(entry.getResourceId()).isEqualTo(incidentId);
        assertThat(entry.getMessage()).isNotBlank();

        // Secret must never appear in any audit field
        String auditText = entry.getAction() + " " + entry.getMessage();
        assertThat(auditText).doesNotContain(RAW_JWT);
    }

    // (b) Successful runbook generation creates RUNBOOK_GENERATED SUCCESS entry.
    @Test
    void runbook_createsAuditLogEntry() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId, "Service error");

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk());

        var entries = auditLogRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(AuditAction.RUNBOOK_GENERATED);
        assertThat(entries.get(0).getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(entries.get(0).getResourceId()).isEqualTo(incidentId);
    }

    // (c) Successful postmortem generation creates POSTMORTEM_GENERATED SUCCESS entry.
    @Test
    void postmortem_createsAuditLogEntry() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId, "Service error");

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk());

        var entries = auditLogRepository.findAll();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getAction()).isEqualTo(AuditAction.POSTMORTEM_GENERATED);
        assertThat(entries.get(0).getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(entries.get(0).getResourceId()).isEqualTo(incidentId);
    }

    // (d) A 429 response creates USAGE_LIMIT_BLOCKED BLOCKED entry;
    //     entry persists even though the generation transaction was rolled back.
    @Test
    void usageLimitBlocked_createsAuditLogEntry() throws Exception {
        UUID tenantId   = createTenant(Plan.FREE);
        UUID incidentId = createIncidentForTenant(tenantId, "Service error");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                    .param("tenantId", tenantId.toString()))
                .andExpect(status().isOk());
        }

        // Clear SUCCESS entries so we can assert only the BLOCKED entry
        auditLogRepository.deleteAll();

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isTooManyRequests());

        var entries = auditLogRepository.findAll();
        assertThat(entries).hasSize(1);

        var entry = entries.get(0);
        assertThat(entry.getAction()).isEqualTo(AuditAction.USAGE_LIMIT_BLOCKED);
        assertThat(entry.getStatus()).isEqualTo(AuditStatus.BLOCKED);
        assertThat(entry.getTenantId()).isEqualTo(tenantId);
        assertThat(entry.getResourceType()).isEqualTo("USAGE");
        assertThat(entry.getResourceId()).isNull();
        assertThat(entry.getMessage()).contains("AI_ANALYSIS");
    }

    // (e) Audit log is tenant-scoped: tenant A's entries do not appear for tenant B.
    @Test
    void auditLog_isTenantScoped() throws Exception {
        UUID tenantA    = createTenant(Plan.STARTER);
        UUID tenantB    = createTenant(Plan.STARTER);
        UUID incidentA  = createIncidentForTenant(tenantA, "Error in service A");
        UUID incidentB  = createIncidentForTenant(tenantB, "Error in service B");

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentA)
                .param("tenantId", tenantA.toString()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentB)
                .param("tenantId", tenantB.toString()))
            .andExpect(status().isOk());

        String bodyA = mockMvc.perform(get("/api/v1/tenants/{id}/audit-logs", tenantA))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String bodyB = mockMvc.perform(get("/api/v1/tenants/{id}/audit-logs", tenantB))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode pageA = objectMapper.readTree(bodyA).at("/data/page");
        JsonNode pageB = objectMapper.readTree(bodyB).at("/data/page");

        assertThat(pageA.at("/totalElements").asInt()).isEqualTo(1);
        assertThat(pageB.at("/totalElements").asInt()).isEqualTo(1);

        // Tenant A's entry must not contain tenant B's ID
        assertThat(bodyA).doesNotContain(tenantB.toString());
        assertThat(bodyB).doesNotContain(tenantA.toString());
    }

    // (f) GET /audit-logs returns correct paginated structure with all required fields.
    @Test
    void getAuditLogs_returnsPagedResults() throws Exception {
        UUID tenantId   = createTenant(Plan.STARTER);
        UUID incidentId = createIncidentForTenant(tenantId, "Service error");

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
                .param("tenantId", tenantId.toString()))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/tenants/{id}/audit-logs", tenantId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.content").isArray())
            .andExpect(jsonPath("$.data.page.totalElements").value(2))
            .andExpect(jsonPath("$.data.content[0].id").isNotEmpty())
            .andExpect(jsonPath("$.data.content[0].tenantId").value(tenantId.toString()))
            .andExpect(jsonPath("$.data.content[0].action").isNotEmpty())
            .andExpect(jsonPath("$.data.content[0].status").isNotEmpty())
            .andExpect(jsonPath("$.data.content[0].createdAt").isNotEmpty());
    }

    // (g) GET /audit-logs for an unknown tenant returns 404 with standard ErrorResponse shape.
    @Test
    void getAuditLogs_unknownTenant_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/{id}/audit-logs",
                "00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID createTenant(Plan plan) throws Exception {
        var request = new CreateTenantRequest("Test Tenant", plan, 0, 30);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());
    }

    private UUID createIncidentForTenant(UUID tenantId, String message) throws Exception {
        ingestEvent(tenantId, "test-svc", LogLevel.ERROR, message);
        String body = mockMvc.perform(get("/api/v1/incidents")
                .param("tenantId", tenantId.toString()))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(
            objectMapper.readTree(body).at("/data/content/0/id").asText()
        );
    }

    private void ingestEvent(UUID tenantId, String serviceId, LogLevel level, String message)
        throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId(serviceId);
        req.setLevel(level);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }
}
