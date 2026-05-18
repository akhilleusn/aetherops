package com.aetherops.postmortem;

import com.aetherops.analysis.model.RiskLevel;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.model.EvidenceType;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.postmortem.model.PostmortemDraftResponse;
import com.aetherops.postmortem.model.PostmortemEvaluationResult;
import com.aetherops.postmortem.model.TimelineEntry;
import com.aetherops.postmortem.service.PostmortemEvaluationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PostmortemEvaluationServiceTest {

    private final PostmortemEvaluationService service = new PostmortemEvaluationService();

    private static final UUID INCIDENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVIDENCE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // (a) Valid postmortem passes all 5 checks
    @Test
    void evaluate_validDraft_passes() {
        PostmortemEvaluationResult result = service.evaluate(validDraft(), validPack());

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getChecksPerformed()).isEqualTo(5);
        assertThat(result.getCheckedAt()).isNotNull();
    }

    // (b) Blank incidentSummary → MEDIUM
    @Test
    void evaluate_blankIncidentSummary_fails() {
        var draft = validDraft().toBuilder().incidentSummary("").build();

        PostmortemEvaluationResult result = service.evaluate(draft, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("incidentsummary"));
    }

    // (c) Blank likelyCause → MEDIUM
    @Test
    void evaluate_blankLikelyCause_fails() {
        var draft = validDraft().toBuilder().likelyCause("  ").build();

        PostmortemEvaluationResult result = service.evaluate(draft, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("likelycause"));
    }

    // (d) Timeline entry with blank description → MEDIUM
    @Test
    void evaluate_blankTimelineEntryDescription_fails() {
        var timeline = List.of(
            TimelineEntry.builder()
                .timestamp(Instant.now())
                .description("")
                .build()
        );
        var draft = validDraft().toBuilder().timeline(timeline).build();

        PostmortemEvaluationResult result = service.evaluate(draft, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("timeline entry 1"));
    }

    // (e) Raw JWT token in a timeline entry description → HIGH (secret leakage)
    @Test
    void evaluate_secretInTimelineEntry_fails() {
        String rawJwt = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        var timeline = List.of(
            TimelineEntry.builder()
                .timestamp(Instant.now())
                .description("Auth failed with token: " + rawJwt)
                .evidenceRef(EVIDENCE_ID.toString())
                .build()
        );
        var draft = validDraft().toBuilder().timeline(timeline).build();

        PostmortemEvaluationResult result = service.evaluate(draft, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i ->
            i.toLowerCase().contains("credential") || i.toLowerCase().contains("token"));
    }

    // (f) Destructive action in followUpActions → HIGH
    @Test
    void evaluate_destructiveFollowUpAction_fails() {
        var draft = validDraft().toBuilder()
            .followUpActions(List.of(
                "Review error handling in payment-svc",
                "DROP TABLE incidents to clean old data"
            ))
            .build();

        PostmortemEvaluationResult result = service.evaluate(draft, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains("drop table"));
    }

    // (g) Hallucinated evidenceRef in timeline → HIGH
    @Test
    void evaluate_fakeEvidenceRefInTimeline_fails() {
        UUID fakeRef = UUID.fromString("00000000-0000-0000-0000-000000000000");
        var timeline = List.of(
            TimelineEntry.builder()
                .timestamp(Instant.now())
                .description("ERROR log: Database connection refused")
                .evidenceRef(fakeRef.toString())
                .build()
        );
        var draft = validDraft().toBuilder().timeline(timeline).build();

        PostmortemEvaluationResult result = service.evaluate(draft, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains(fakeRef.toString()));
    }

    // --- fixtures ---

    private PostmortemDraftResponse validDraft() {
        return PostmortemDraftResponse.builder()
            .id(UUID.randomUUID())
            .incidentId(INCIDENT_ID)
            .tenantId(TENANT_ID)
            .draftVersion(1)
            .evidenceCount(1)
            .analysisEnriched(false)
            .incidentSummary("An incident was detected in service 'payment-svc' at 2024-01-01T00:00:00Z.")
            .timeline(List.of(
                TimelineEntry.builder()
                    .timestamp(Instant.now().minusSeconds(120))
                    .description("Incident first detected in payment-svc.")
                    .build(),
                TimelineEntry.builder()
                    .timestamp(Instant.now().minusSeconds(60))
                    .description("ERROR log event recorded: \"Database connection refused\".")
                    .evidenceRef(EVIDENCE_ID.toString())
                    .build()
            ))
            .likelyCause("Based on log evidence [" + EVIDENCE_ID + "]: Database connection refused. "
                + "Root cause has not been confirmed — further investigation is required.")
            .impact("Service 'payment-svc' experienced errors for 2 minutes. 1 log event recorded. Severity: MEDIUM.")
            .dataGaps(List.of(
                "No metrics available — cannot assess resource utilisation.",
                "No distributed traces available.",
                "No deployment history available."
            ))
            .followUpActions(List.of(
                "Review and document the confirmed root cause once the investigation is complete.",
                "Add a metric alert for elevated error rates in 'payment-svc'."
            ))
            .generatorVersion("mock-v1")
            .createdAt(Instant.now())
            .build();
    }

    private IncidentEvidencePackResponse validPack() {
        EvidenceItemResponse item = EvidenceItemResponse.builder()
            .id(EVIDENCE_ID)
            .incidentId(INCIDENT_ID)
            .logEventId(UUID.randomUUID())
            .type(EvidenceType.LOG_EVENT)
            .sourceRef(EVIDENCE_ID.toString())
            .summary("Database connection refused")
            .level("ERROR")
            .timestamp(Instant.now())
            .build();

        return IncidentEvidencePackResponse.builder()
            .incidentId(INCIDENT_ID)
            .tenantId(TENANT_ID)
            .title("[ERROR] payment-svc: Database connection refused")
            .serviceId("payment-svc")
            .severity(IncidentSeverity.MEDIUM)
            .status(IncidentStatus.OPEN)
            .firstSeen(Instant.now().minusSeconds(120))
            .lastSeen(Instant.now())
            .totalEvidenceCount(1)
            .evidence(List.of(item))
            .build();
    }
}
