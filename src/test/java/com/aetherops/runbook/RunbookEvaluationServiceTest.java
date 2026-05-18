package com.aetherops.runbook;

import com.aetherops.analysis.model.RiskLevel;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.model.EvidenceType;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.runbook.model.RunbookEvaluationResult;
import com.aetherops.runbook.model.RunbookPhase;
import com.aetherops.runbook.model.RunbookStep;
import com.aetherops.runbook.model.RunbookSuggestionResponse;
import com.aetherops.runbook.service.RunbookEvaluationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunbookEvaluationServiceTest {

    private final RunbookEvaluationService service = new RunbookEvaluationService();

    private static final UUID INCIDENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_ID   = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EVIDENCE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // (a) Valid runbook passes all 5 checks
    @Test
    void evaluate_validRunbook_passes() {
        RunbookEvaluationResult result = service.evaluate(validRunbook(), validPack());

        assertThat(result.isPassed()).isTrue();
        assertThat(result.getIssues()).isEmpty();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getChecksPerformed()).isEqualTo(5);
        assertThat(result.getCheckedAt()).isNotNull();
    }

    // (b) Empty steps list → HIGH
    @Test
    void evaluate_emptySteps_fails() {
        var runbook = validRunbook().toBuilder().steps(List.of()).build();

        RunbookEvaluationResult result = service.evaluate(runbook, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("no steps"));
    }

    // (c) Step with blank action → MEDIUM
    @Test
    void evaluate_blankAction_fails() {
        var steps = List.of(
            RunbookStep.builder()
                .phase(RunbookPhase.TRIAGE)
                .action("   ")
                .rationale("Check service health")
                .build()
        );
        var runbook = validRunbook().toBuilder().steps(steps).build();

        RunbookEvaluationResult result = service.evaluate(runbook, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("blank action"));
    }

    // (d) Step with blank rationale → MEDIUM
    @Test
    void evaluate_blankRationale_fails() {
        var steps = List.of(
            RunbookStep.builder()
                .phase(RunbookPhase.INVESTIGATE)
                .action("Review the deployment history")
                .rationale("")
                .build()
        );
        var runbook = validRunbook().toBuilder().steps(steps).build();

        RunbookEvaluationResult result = service.evaluate(runbook, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.getIssues()).anyMatch(i -> i.toLowerCase().contains("blank rationale"));
    }

    // (e) Step with destructive action → HIGH
    @Test
    void evaluate_unsafeAction_fails() {
        var steps = List.of(
            RunbookStep.builder()
                .phase(RunbookPhase.INVESTIGATE)
                .action("DROP TABLE incidents to clear old data")
                .rationale("Free up space")
                .build()
        );
        var runbook = validRunbook().toBuilder().steps(steps).build();

        RunbookEvaluationResult result = service.evaluate(runbook, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains("drop table"));
    }

    // (f) Step with raw JWT token in action text → HIGH (secret leakage)
    @Test
    void evaluate_secretLeakageInStepText_fails() {
        String rawJwt = "eyJhbGciOiJSUzI1NiJ9.payload.signature";
        var steps = List.of(
            RunbookStep.builder()
                .phase(RunbookPhase.INVESTIGATE)
                .action("Check this token: " + rawJwt)
                .rationale("The token was found in the logs")
                .build()
        );
        var runbook = validRunbook().toBuilder().steps(steps).build();

        RunbookEvaluationResult result = service.evaluate(runbook, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i ->
            i.toLowerCase().contains("credential") || i.toLowerCase().contains("token"));
    }

    // (g) Step with a hallucinated evidenceRef → HIGH
    @Test
    void evaluate_fakeEvidenceRef_fails() {
        UUID fakeRef = UUID.fromString("00000000-0000-0000-0000-000000000000");
        var steps = List.of(
            RunbookStep.builder()
                .phase(RunbookPhase.INVESTIGATE)
                .action("Review evidence item " + fakeRef)
                .rationale("This item is from the evidence pack")
                .evidenceRef(fakeRef.toString())
                .build()
        );
        var runbook = validRunbook().toBuilder().steps(steps).build();

        RunbookEvaluationResult result = service.evaluate(runbook, validPack());

        assertThat(result.isPassed()).isFalse();
        assertThat(result.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getIssues()).anyMatch(i -> i.contains(fakeRef.toString()));
    }

    // --- fixtures ---

    private RunbookSuggestionResponse validRunbook() {
        return RunbookSuggestionResponse.builder()
            .id(UUID.randomUUID())
            .incidentId(INCIDENT_ID)
            .tenantId(TENANT_ID)
            .runbookVersion(1)
            .evidenceCount(1)
            .steps(List.of(
                RunbookStep.builder()
                    .phase(RunbookPhase.TRIAGE)
                    .action("Confirm the current operational status of payment-svc.")
                    .rationale("Establishing whether the service is still running is the first safe, read-only check.")
                    .build(),
                RunbookStep.builder()
                    .phase(RunbookPhase.INVESTIGATE)
                    .action("Review evidence item " + EVIDENCE_ID + " — ERROR log: \"Database connection refused\".")
                    .rationale("This log event is part of the incident evidence pack. Review it in surrounding log context.")
                    .evidenceRef(EVIDENCE_ID.toString())
                    .build(),
                RunbookStep.builder()
                    .phase(RunbookPhase.VERIFY)
                    .action("Cross-reference the suspected root cause against at least two independent evidence items.")
                    .rationale("Triangulating across multiple evidence items reduces the risk of acting on a misdiagnosis.")
                    .build()
            ))
            .generationBasis("log-only")
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
            .firstSeen(Instant.now().minusSeconds(60))
            .lastSeen(Instant.now())
            .totalEvidenceCount(1)
            .evidence(List.of(item))
            .build();
    }
}
