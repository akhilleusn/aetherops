package com.aetherops.runbook.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.runbook.model.*;
import com.aetherops.runbook.repository.RunbookSuggestionRepository;
import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.service.UsageLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RunbookService {

    private final EvidencePackService evidencePackService;
    private final RunbookGenerator runbookGenerator;
    private final RunbookEvaluationService evaluationService;
    private final RunbookSuggestionRepository runbookSuggestionRepository;
    private final UsageLimitService usageLimitService;
    private final AuditLogService auditLogService;

    /**
     * Each call creates a new RunbookSuggestion row (append-only).
     * Rationale: as evidence grows between calls, each runbook reflects the data available at that moment.
     */
    @Transactional
    public RunbookSuggestionResponse generate(UUID incidentId, UUID tenantId) {
        usageLimitService.checkAndIncrement(tenantId, UsageType.RUNBOOK_GENERATION);
        IncidentEvidencePackResponse pack = evidencePackService.getEvidencePack(incidentId, tenantId);
        AIAnalysisContext context = buildContext(pack);
        RunbookGenerationResult result = runbookGenerator.generate(context);

        int version = (int) runbookSuggestionRepository.countByIncidentId(incidentId) + 1;

        RunbookSuggestion saved = runbookSuggestionRepository.saveAndFlush(RunbookSuggestion.builder()
            .incidentId(incidentId)
            .tenantId(tenantId)
            .runbookVersion(version)
            .evidenceCount(pack.getTotalEvidenceCount())
            .steps(result.getSteps())
            .generationBasis(result.getGenerationBasis())
            .generatorVersion(result.getGeneratorVersion())
            .build());

        log.info("Created runbook id={} incidentId={} version={} steps={} basis={}",
            saved.getId(), incidentId, version, result.getSteps().size(), result.getGenerationBasis());

        RunbookSuggestionResponse response = toResponse(saved);

        auditLogService.record(
            tenantId, AuditAction.RUNBOOK_GENERATED, AuditStatus.SUCCESS,
            "INCIDENT", incidentId,
            "Runbook v" + saved.getRunbookVersion() + " generated for incident " + incidentId
        );

        RunbookEvaluationResult evaluation = evaluationService.evaluate(response, pack);
        if (!evaluation.isPassed()) {
            log.warn("Runbook id={} failed evaluation: riskLevel={} issues=[{}]",
                saved.getId(), evaluation.getRiskLevel(),
                String.join("; ", evaluation.getIssues()));
        } else {
            log.debug("Runbook id={} passed all {} evaluation checks",
                saved.getId(), evaluation.getChecksPerformed());
        }

        return response;
    }

    public RunbookSuggestionResponse getLatest(UUID incidentId, UUID tenantId) {
        return runbookSuggestionRepository
            .findTopByIncidentIdAndTenantIdOrderByCreatedAtDesc(incidentId, tenantId)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No runbook suggestion found for incident " + incidentId));
    }

    private AIAnalysisContext buildContext(IncidentEvidencePackResponse pack) {
        List<AIAnalysisContext.EvidenceContext> evidenceContexts = pack.getEvidence().stream()
            .map(item -> AIAnalysisContext.EvidenceContext.builder()
                .evidenceItemId(item.getId().toString())
                .type(item.getType().name())
                .summary(item.getSummary())
                .level(item.getLevel())
                .timestamp(item.getTimestamp())
                .build())
            .toList();

        return AIAnalysisContext.builder()
            .incidentId(pack.getIncidentId())
            .tenantId(pack.getTenantId())
            .incidentTitle(pack.getTitle())
            .serviceId(pack.getServiceId())
            .severity(pack.getSeverity() != null ? pack.getSeverity().name() : null)
            .status(pack.getStatus() != null ? pack.getStatus().name() : null)
            .firstSeen(pack.getFirstSeen())
            .lastSeen(pack.getLastSeen())
            .evidenceItems(evidenceContexts)
            .build();
    }

    private RunbookSuggestionResponse toResponse(RunbookSuggestion suggestion) {
        return RunbookSuggestionResponse.builder()
            .id(suggestion.getId())
            .incidentId(suggestion.getIncidentId())
            .tenantId(suggestion.getTenantId())
            .runbookVersion(suggestion.getRunbookVersion())
            .evidenceCount(suggestion.getEvidenceCount())
            .steps(suggestion.getSteps())
            .generationBasis(suggestion.getGenerationBasis())
            .generatorVersion(suggestion.getGeneratorVersion())
            .createdAt(suggestion.getCreatedAt())
            .build();
    }
}
