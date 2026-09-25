package com.aetherops.publicdemo;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.analysis.model.AIAnalysis;
import com.aetherops.analysis.model.AnalysisStatus;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.evidence.model.EvidenceItem;
import com.aetherops.evidence.model.EvidenceType;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.ingestion.repository.LogEventRepository;
import com.aetherops.oncall.IncidentTimeline.TimelineEventType;
import com.aetherops.oncall.SeverityLevel;
import com.aetherops.postmortem.model.PostmortemDraft;
import com.aetherops.postmortem.model.TimelineEntry;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.runbook.model.RunbookPhase;
import com.aetherops.runbook.model.RunbookStep;
import com.aetherops.runbook.model.RunbookSuggestion;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared setup for the public demo security tests. Extends AbstractIntegrationTest WITHOUT
 * @ActiveProfiles("test"): that profile swaps in the permit-all TestSecurityConfig, under which
 * every one of these tests would pass no matter what the real SecurityConfig does. Real Postgres
 * (RLS enforced, non-superuser app role) and Redis come from AbstractIntegrationTest.
 */
@AutoConfigureMockMvc
abstract class AbstractPublicDemoTest extends AbstractIntegrationTest {

    static final String PREFIX       = "/api/v1/public-demo";
    static final UUID   DEMO_TENANT  = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    static final UUID   OTHER_TENANT = UUID.fromString("d0000000-0000-4000-8000-000000000002");

    record SeededIncident(UUID incidentId, UUID logEventId) {}

    @Autowired MockMvc                     mockMvc;
    @Autowired JdbcTemplate                jdbcTemplate;
    @Autowired IncidentRepository          incidentRepository;
    @Autowired LogEventRepository          logEventRepository;
    @Autowired EvidenceItemRepository      evidenceItemRepository;
    @Autowired AIAnalysisRepository        aiAnalysisRepository;
    @Autowired RunbookSuggestionRepository runbookRepository;
    @Autowired PostmortemDraftRepository   postmortemRepository;

    @BeforeEach
    void createTenants() {   // runs after AbstractIntegrationTest.cleanState truncates everything
        jdbcTemplate.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Public Demo', 'STARTER')", DEMO_TENANT);
        jdbcTemplate.update("INSERT INTO tenants (id, name, plan) VALUES (?, 'Private Customer', 'STARTER')", OTHER_TENANT);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    /**
     * One incident with every kind of record a demo route can expose, including the fields that
     * must never leak. RESOLVED so the escalation / SLA / war-room schedulers leave it alone
     * while a test snapshots the database.
     */
    SeededIncident seedIncident(UUID tenantId, String title) {
        SeededIncident seeded = withTenant(tenantId, () -> {
            Instant t0 = Instant.now().minusSeconds(600);
            Incident incident = incidentRepository.save(Incident.builder()
                .tenantId(tenantId).title(title)
                .status(IncidentStatus.RESOLVED).severity(IncidentSeverity.HIGH)
                .serviceId("checkout-api").environment("simulation")
                .fingerprint("fp-" + UUID.randomUUID()).eventCount(3)
                .firstSeenAt(t0).lastSeenAt(t0.plusSeconds(120)).resolvedAt(t0.plusSeconds(300))
                .description("internal description")
                .resolutionNotes("internal resolution notes")
                .tags(Map.of("owner", "private-tag"))
                .build());
            LogEvent log = logEventRepository.save(LogEvent.builder()
                .tenantId(tenantId).serviceId("checkout-api").environment("simulation")
                .level(LogLevel.ERROR)
                .message("HikariPool-1 - Connection is not available, request timed out after 30000ms.")
                .rawPayload("{\"internal\":\"raw payload\"}")
                .traceId("trace-private").spanId("span-private")
                .occurredAt(t0)
                .build());
            evidenceItemRepository.save(EvidenceItem.builder()
                .incidentId(incident.getId()).tenantId(tenantId).logEventId(log.getId())
                .type(EvidenceType.LOG_EVENT).sourceRef(log.getId().toString())
                .summary(log.getMessage()).level("ERROR").timestamp(t0)
                .build());
            insertTimeline(tenantId, incident.getId(), TimelineEventType.CREATED, "Incident created: " + title, t0);
            insertTimeline(tenantId, incident.getId(), TimelineEventType.ENGINEER_PAGED, "Paged alice@example.com via SMS", t0.plusSeconds(1));
            insertTimeline(tenantId, incident.getId(), TimelineEventType.SLACK_ACTION_REQUESTED, "Slack user U123 (bob@example.com) clicked Resolve", t0.plusSeconds(2));
            aiAnalysisRepository.save(AIAnalysis.builder()
                .incidentId(incident.getId()).tenantId(tenantId)
                .summary("Pool exhausted").likelyCause("Connection leak").confidenceScore(0.55)
                .evidenceRefs(List.of()).unknowns(List.of("No metrics")).recommendedNextChecks(List.of("Check pool metrics"))
                .analysisVersion(1).evidenceCount(1).model("claude-opus-5").promptVersion("2.0")
                .status(AnalysisStatus.COMPLETED).createdAt(Instant.now())
                .build());
            runbookRepository.save(RunbookSuggestion.builder()
                .incidentId(incident.getId()).tenantId(tenantId).runbookVersion(1).evidenceCount(1)
                .steps(List.of(RunbookStep.builder().phase(RunbookPhase.TRIAGE).action("Check pool metrics").rationale("Pool timeouts").build()))
                .generationBasis("evidence").generatorVersion("1").createdAt(Instant.now())
                .build());
            postmortemRepository.save(PostmortemDraft.builder()
                .incidentId(incident.getId()).tenantId(tenantId).draftVersion(1).evidenceCount(1).analysisEnriched(true)
                .incidentSummary("Pool exhaustion").likelyCause("Connection leak").impact("Checkout errors")
                .timeline(List.of(TimelineEntry.builder().timestamp(t0).description("First timeout").build()))
                .dataGaps(List.of("No metrics")).followUpActions(List.of("Add pool alerting"))
                .generatorVersion("1").slackDiscussionSummary("private slack discussion").createdAt(Instant.now())
                .build());
            return new SeededIncident(incident.getId(), log.getId());
        });
        TenantContext.clear();
        return seeded;
    }

    // Plain SQL with explicit created_at: incident_timeline's primary key is (incident_id,
    // created_at), and several entries saved through JPA in one transaction can receive the same
    // @CreationTimestamp value and collide. Runs inside seedIncident's withTenant transaction, so
    // RLS sees app.tenant_id.
    private void insertTimeline(UUID tenantId, UUID incidentId, TimelineEventType type, String detail, Instant at) {
        jdbcTemplate.update(
            "INSERT INTO incident_timeline (tenant_id, incident_id, event_type, severity, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            tenantId, incidentId, type.name(), SeverityLevel.P2.name(), detail, Timestamp.from(at));
    }
}
