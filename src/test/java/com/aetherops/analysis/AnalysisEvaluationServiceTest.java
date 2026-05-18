package com.aetherops.analysis;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.analysis.model.AnalysisEvaluationResult;
import com.aetherops.analysis.model.RiskLevel;
import com.aetherops.analysis.service.AnalysisEvaluationService;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.model.EvidenceType;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisEvaluationServiceTest {

    private final AnalysisEvaluationService service = new AnalysisEvaluationService();

    private static final UUID INCIDENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVIDENCE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // (a) Valid mock-style analysis passes all 8 checks
    @Test
    void evaluate_validAnalysis_passes() {
        var result = service.evaluate(validAnalysis(), validPack());

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getChecksPerformed()).isEqualTo(8);
        assertThat(result.getCheckedAt()).isNotNull();
    }

    // (b) likelyCause present but evidenceRefs empty → HIGH
    @Test
    void evaluate_emptyEvidenceRefs_fails() {
        var analysis = validAnalysis().toBuilder()
            .evidenceRefs(List.of())
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("evidencerefs"));
    }

    // (c) evidenceRef not present in evidence pack → HIGH (hallucinated reference)
    @Test
    void evaluate_fakeEvidenceRef_fails() {
        UUID fakeRef = UUID.fromString("00000000-0000-0000-0000-000000000000");
        var analysis = validAnalysis().toBuilder()
            .evidenceRefs(List.of(fakeRef.toString()))
            .likelyCause("Based on log evidence [" + fakeRef + "]: Database connection refused")
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains(fakeRef.toString()));
    }

    // (d) likelyCause claims metric data when only LOG_EVENT evidence exists → HIGH
    @Test
    void evaluate_unsupportedMetricClaim_fails() {
        var analysis = validAnalysis().toBuilder()
            .likelyCause("CPU usage was 100% causing the connection pool to exhaust")
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains("cpu usage"));
    }

    // (e) confidenceScore unrealistically high for log-only weak evidence → MEDIUM
    @Test
    void evaluate_highConfidenceWithWeakEvidence_fails() {
        var analysis = validAnalysis().toBuilder()
            .confidenceScore(0.95)
            .build();

        var result = service.evaluate(analysis, validPack()); // 1 LOG_EVENT item

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.contains("0.95"));
    }

    // (f) recommendedNextChecks contains a destructive action → HIGH
    @Test
    void evaluate_unsafeRecommendation_fails() {
        var analysis = validAnalysis().toBuilder()
            .recommendedNextChecks(List.of(
                "DROP TABLE incidents to clear old data",
                "Review service logs for the affected time window"
            ))
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains("drop table"));
    }

    // (g) Raw JWT token present in likelyCause → HIGH (secret leakage)
    @Test
    void evaluate_secretLeakageInLikelyCause_fails() {
        String rawJwt = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        var analysis = validAnalysis().toBuilder()
            .likelyCause("Auth error — raw token: " + rawJwt
                + " from evidence [" + EVIDENCE_ID + "]")
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i ->
            i.toLowerCase().contains("credential") || i.toLowerCase().contains("token"));
    }

    // (h) unknowns does not mention metrics, traces, or deployment → MEDIUM
    @Test
    void evaluate_missingUnknowns_fails() {
        var analysis = validAnalysis().toBuilder()
            .unknowns(List.of("No additional information available"))
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("unknowns"));
    }

    // (i) certainty language with weak evidence → MEDIUM
    @Test
    void evaluate_certaintyLanguageWithWeakEvidence_fails() {
        var analysis = validAnalysis().toBuilder()
            .confidenceScore(0.35)
            .likelyCause("This is definitely a database connection issue — evidence ["
                + EVIDENCE_ID + "]")
            .build();

        var result = service.evaluate(analysis, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.contains("definitely"));
    }

    // --- fixtures ---

    private AIAnalysisResponse validAnalysis() {
        return AIAnalysisResponse.builder()
            .id(UUID.randomUUID())
            .incidentId(INCIDENT_ID)
            .tenantId(TENANT_ID)
            .summary("Incident in service 'payment-svc': Database connection refused " +
                "— supported by 1 log evidence item. Confidence is limited to log data only.")
            .likelyCause("Based on log evidence [" + EVIDENCE_ID + "]: Database connection refused")
            .confidenceScore(0.35)
            .severity("MEDIUM")
            .evidenceRefs(List.of(EVIDENCE_ID.toString()))
            .unknowns(List.of(
                "No metrics available — cannot assess resource utilisation or error rates",
                "No distributed traces available — cannot identify upstream propagation",
                "No deployment history available — cannot correlate with recent changes"
            ))
            .recommendedNextChecks(List.of(
                "Review service logs for the affected time window",
                "Check recent deployment history for the affected service"
            ))
            .model("mock-v1")
            .promptVersion("1.0")
            .analysisVersion(1)
            .evidenceCount(1)
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
            .firstSeen(Instant.now().minusSeconds(60))
            .lastSeen(Instant.now())
            .totalEvidenceCount(1)
            .evidence(List.of(item))
            .build();
    }
}
