package com.aetherops.retention;

import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.audit.repository.AuditLogRepository;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEventRequest;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.tenant.TenantContext;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.aetherops.usage.repository.TenantMonthlyUsageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.aetherops.AbstractIntegrationTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@ActiveProfiles("test")
class RetentionIntegrationTest extends AbstractIntegrationTest {

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   objectMapper;
    @Autowired JdbcTemplate   jdbcTemplate;

    @Autowired TenantRepository               tenantRepository;
    @Autowired AuditLogRepository             auditLogRepository;
    @Autowired TenantMonthlyUsageRepository   usageRepository;
    @Autowired PostmortemDraftRepository      postmortemDraftRepository;
    @Autowired RunbookSuggestionRepository    runbookSuggestionRepository;
    @Autowired AIAnalysisRepository           aiAnalysisRepository;
    @Autowired EvidenceItemRepository         evidenceItemRepository;
    @Autowired IncidentRepository             incidentRepository;
    @Autowired LogEventRepository             logEventRepository;

    private static final int RETENTION_DAYS = 30;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        postmortemDraftRepository.deleteAll();
        runbookSuggestionRepository.deleteAll();
        aiAnalysisRepository.deleteAll();
        evidenceItemRepository.deleteAll();
        incidentRepository.deleteAll();
        logEventRepository.deleteAll();
        usageRepository.deleteAll();
        tenantRepository.deleteAll();
    }

    // (1) Dry-run returns correct counts but deletes nothing.
    @Test
    void dryRun_returnsCorrectCountsWithoutDeleting() throws Exception {
        UUID tenantId = createTenant(Plan.STARTER);
        createFullIncidentLifecycle(tenantId);
        backdateAll(tenantId, daysAgo(RETENTION_DAYS + 10));

        mockMvc.perform(post("/api/v1/tenants/{id}/retention/run", tenantId).with(asTenant(tenantId))
                .param("dryRun", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.logEventsAffected").value(1))
            .andExpect(jsonPath("$.data.evidenceItemsAffected").value(1))
            .andExpect(jsonPath("$.data.incidentsAffected").value(1))
            .andExpect(jsonPath("$.data.analysesAffected").value(1))
            .andExpect(jsonPath("$.data.runbooksAffected").value(1))
            .andExpect(jsonPath("$.data.postmortemsAffected").value(1))
            .andExpect(jsonPath("$.data.totalAffected").value(6));

        // Nothing deleted — all rows still present
        assertThat(withTenant(tenantId, () -> logEventRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> evidenceItemRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> incidentRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> aiAnalysisRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> runbookSuggestionRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> postmortemDraftRepository.count())).isEqualTo(1);
    }

    // (2) Real run deletes only rows outside the retention window.
    @Test
    void realRun_deletesOnlyExpiredData() throws Exception {
        UUID tenantId = createTenant(Plan.STARTER);
        createFullIncidentLifecycle(tenantId);
        backdateAll(tenantId, daysAgo(RETENTION_DAYS + 10));

        mockMvc.perform(post("/api/v1/tenants/{id}/retention/run", tenantId).with(asTenant(tenantId))
                .param("dryRun", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(false))
            .andExpect(jsonPath("$.data.totalAffected").value(6));

        assertThat(withTenant(tenantId, () -> logEventRepository.count())).isEqualTo(0);
        assertThat(withTenant(tenantId, () -> evidenceItemRepository.count())).isEqualTo(0);
        assertThat(withTenant(tenantId, () -> incidentRepository.count())).isEqualTo(0);
        assertThat(withTenant(tenantId, () -> aiAnalysisRepository.count())).isEqualTo(0);
        assertThat(withTenant(tenantId, () -> runbookSuggestionRepository.count())).isEqualTo(0);
        assertThat(withTenant(tenantId, () -> postmortemDraftRepository.count())).isEqualTo(0);
    }

    // (3) Records newer than the cutoff are NOT deleted.
    @Test
    void realRun_preservesRecentData() throws Exception {
        UUID tenantId = createTenant(Plan.STARTER);

        // Create one old lifecycle (will be deleted) and one recent one (will be preserved)
        createFullIncidentLifecycle(tenantId, "Old error — should be deleted");
        backdateAll(tenantId, daysAgo(RETENTION_DAYS + 10));

        createFullIncidentLifecycle(tenantId, "Recent error — must survive");
        // Recent data has current timestamps — no backdating

        mockMvc.perform(post("/api/v1/tenants/{id}/retention/run", tenantId).with(asTenant(tenantId))
                .param("dryRun", "false"))
            .andExpect(status().isOk());

        // One log event from the recent lifecycle must remain
        assertThat(withTenant(tenantId, () -> logEventRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> evidenceItemRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> incidentRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> aiAnalysisRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> runbookSuggestionRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> postmortemDraftRepository.count())).isEqualTo(1);
    }

    // (4) Retention for tenant A never touches tenant B's data.
    @Test
    void tenantIsolation_neverDeletesOtherTenantData() throws Exception {
        UUID tenantA = createTenant(Plan.STARTER);
        UUID tenantB = createTenant(Plan.STARTER);

        createFullIncidentLifecycle(tenantA);
        createFullIncidentLifecycle(tenantB);

        // Backdate both tenants' data past the retention window
        backdateAll(tenantA, daysAgo(RETENTION_DAYS + 10));
        backdateAll(tenantB, daysAgo(RETENTION_DAYS + 10));

        // Run retention for tenant A only
        mockMvc.perform(post("/api/v1/tenants/{id}/retention/run", tenantA).with(asTenant(tenantA))
                .param("dryRun", "false"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.logEventsAffected").value(1));

        // Tenant A's data is gone. RLS scopes each query to a single tenant, so tenant
        // A and tenant B are checked under separate TenantContext values rather than
        // one cross-tenant findAll().
        assertThat(withTenant(tenantA, () -> logEventRepository.findAll()).stream()
            .filter(e -> tenantA.equals(e.getTenantId())).count()).isEqualTo(0);

        // Tenant B's data is completely untouched
        assertThat(withTenant(tenantB, () -> logEventRepository.findAll()).stream()
            .filter(e -> tenantB.equals(e.getTenantId())).count()).isEqualTo(1);
        assertThat(withTenant(tenantB, () -> evidenceItemRepository.findAll()).stream()
            .filter(e -> tenantB.equals(e.getTenantId())).count()).isEqualTo(1);
        assertThat(withTenant(tenantB, () -> incidentRepository.findAll()).stream()
            .filter(i -> tenantB.equals(i.getTenantId())).count()).isEqualTo(1);
    }

    // (5) Unknown tenant returns 404 with standard ErrorResponse shape.
    @Test
    void unknownTenant_returns404() throws Exception {
        UUID unknownTenantId = UUID.fromString("00000000-0000-0000-0000-000000000000");
        mockMvc.perform(post("/api/v1/tenants/{id}/retention/run", unknownTenantId)
                .with(asTenant(unknownTenantId)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andExpect(jsonPath("$.path").isNotEmpty())
            .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // (6) Default (no dryRun param) is safe — nothing is deleted.
    @Test
    void defaultIsDryRun_noDataDeletedWithoutExplicitFlag() throws Exception {
        UUID tenantId = createTenant(Plan.STARTER);
        createFullIncidentLifecycle(tenantId);
        backdateAll(tenantId, daysAgo(RETENTION_DAYS + 10));

        // Call without dryRun parameter
        mockMvc.perform(post("/api/v1/tenants/{id}/retention/run", tenantId).with(asTenant(tenantId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.dryRun").value(true));

        // Nothing deleted
        assertThat(withTenant(tenantId, () -> logEventRepository.count())).isEqualTo(1);
        assertThat(withTenant(tenantId, () -> incidentRepository.count())).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID createTenant(Plan plan) throws Exception {
        // Phase 2 remediation, finding 7 (see STEP_REPORT.md): the API always creates a FREE
        // tenant now -- plan is no longer client-settable. This test needs a specific plan to
        // exercise plan-dependent behavior, so it's set directly via the repository, bypassing
        // the API for test setup (the same principle as the fix itself: plan changes happen
        // through a trusted path, never client-declared self-service).
        var request = new CreateTenantRequest("Test Tenant", RETENTION_DAYS);
        String body = mockMvc.perform(post("/api/v1/tenants")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request))
                .with(asTenant(UUID.randomUUID())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID tenantId = UUID.fromString(objectMapper.readTree(body).at("/data/id").asText());

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
        tenant.setPlan(plan);
        tenantRepository.save(tenant);

        return tenantId;
    }

    private void createFullIncidentLifecycle(UUID tenantId) throws Exception {
        createFullIncidentLifecycle(tenantId, "Service connection error for retention test");
    }

    private void createFullIncidentLifecycle(UUID tenantId, String message) throws Exception {
        ingestEvent(tenantId, message);

        UUID incidentId = UUID.fromString(
            objectMapper.readTree(
                mockMvc.perform(get("/api/v1/incidents").param("tenantId", tenantId.toString()).with(asTenant(tenantId)))
                    .andReturn().getResponse().getContentAsString()
            ).at("/data/content/0/id").asText()
        );

        mockMvc.perform(post("/api/v1/incidents/{id}/analyze", incidentId)
            .param("tenantId", tenantId.toString()).with(asTenant(tenantId))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/runbook", incidentId)
            .param("tenantId", tenantId.toString()).with(asTenant(tenantId))).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/incidents/{id}/postmortem", incidentId)
            .param("tenantId", tenantId.toString()).with(asTenant(tenantId))).andExpect(status().isOk());
    }

    private void ingestEvent(UUID tenantId, String message) throws Exception {
        LogEventRequest req = new LogEventRequest();
        req.setTenantId(tenantId);
        req.setServiceId("retention-test-svc");
        req.setLevel(LogLevel.ERROR);
        req.setMessage(message);
        req.setOccurredAt(Instant.now());

        mockMvc.perform(post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req))
                .with(asTenant(tenantId)))
            .andExpect(status().isCreated());
    }

    /**
     * Backdates all timestamped rows for the given tenant using direct SQL,
     * bypassing @CreationTimestamp which cannot be overridden via JPA.
     *
     * Runs as a single ConnectionCallback (one physical connection, one implicit
     * transaction) so the app.tenant_id session var set here is still in effect —
     * these tables are RLS-protected, and JdbcTemplate calls outside a Spring
     * transaction would otherwise each grab a fresh, unscoped connection from the pool.
     */
    private void backdateAll(UUID tenantId, Instant to) {
        Timestamp ts = Timestamp.from(to);
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
            try (var setTenant = conn.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                setTenant.setString(1, tenantId.toString());
                setTenant.execute();
            }
            try (var ps = conn.prepareStatement("UPDATE log_events SET ingested_at = ? WHERE tenant_id = ?")) {
                ps.setTimestamp(1, ts); ps.setObject(2, tenantId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE evidence_items SET created_at = ? WHERE tenant_id = ?")) {
                ps.setTimestamp(1, ts); ps.setObject(2, tenantId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE incidents SET last_seen_at = ?, first_seen_at = ? WHERE tenant_id = ?")) {
                ps.setTimestamp(1, ts); ps.setTimestamp(2, ts); ps.setObject(3, tenantId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE ai_analyses SET created_at = ? WHERE tenant_id = ?")) {
                ps.setTimestamp(1, ts); ps.setObject(2, tenantId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE runbook_suggestions SET created_at = ? WHERE tenant_id = ?")) {
                ps.setTimestamp(1, ts); ps.setObject(2, tenantId); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("UPDATE postmortem_drafts SET created_at = ? WHERE tenant_id = ?")) {
                ps.setTimestamp(1, ts); ps.setObject(2, tenantId); ps.executeUpdate();
            }
            return null;
        });
    }

    private Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }
}
