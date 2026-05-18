package com.aetherops.analysis.service;

import com.aetherops.analysis.model.*;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisService {

    private final EvidencePackService evidencePackService;
    private final AIProvider aiProvider;
    private final AIAnalysisRepository aiAnalysisRepository;
    private final AnalysisEvaluationService evaluationService;

    /**
     * Each call creates a new AIAnalysis row (append-only).
     * Rationale: as evidence grows between calls, each analysis reflects the data
     * available at that moment. History is preserved for audit and comparison.
     */
    @Transactional
    public AIAnalysisResponse analyze(UUID incidentId, UUID tenantId) {
        // Tenant isolation is enforced here — throws ResourceNotFoundException on mismatch
        IncidentEvidencePackResponse pack = evidencePackService.getEvidencePack(incidentId, tenantId);

        AIAnalysisContext context = buildContext(pack);
        AIAnalysisResult result   = aiProvider.analyze(context);

        int version = (int) aiAnalysisRepository.countByIncidentId(incidentId) + 1;

        AIAnalysis saved = aiAnalysisRepository.saveAndFlush(AIAnalysis.builder()
            .incidentId(incidentId)
            .tenantId(tenantId)
            .summary(result.getSummary())
            .likelyCause(result.getLikelyCause())
            .confidenceScore(result.getConfidenceScore())
            .severity(pack.getSeverity() != null ? pack.getSeverity().name() : null)
            .evidenceRefs(result.getEvidenceRefs())
            .unknowns(result.getUnknowns())
            .recommendedNextChecks(result.getRecommendedNextChecks())
            .model(result.getModel())
            .promptVersion(result.getPromptVersion())
            .analysisVersion(version)
            .evidenceCount(pack.getTotalEvidenceCount())
            .build());

        log.info("Created AI analysis id={} incidentId={} confidence={} evidenceCount={}",
            saved.getId(), incidentId, saved.getConfidenceScore(), pack.getTotalEvidenceCount());

        AIAnalysisResponse response = toResponse(saved);

        AnalysisEvaluationResult evaluation = evaluationService.evaluate(response, pack);
        if (!evaluation.isPassed()) {
            log.warn("AI analysis id={} failed evaluation: riskLevel={} issues=[{}]",
                saved.getId(), evaluation.getRiskLevel(),
                String.join("; ", evaluation.getIssues()));
        } else {
            log.debug("AI analysis id={} passed all {} evaluation checks",
                saved.getId(), evaluation.getChecksPerformed());
        }

        return response;
    }

    private AIAnalysisContext buildContext(IncidentEvidencePackResponse pack) {
        List<AIAnalysisContext.EvidenceContext> evidenceContexts = pack.getEvidence().stream()
            .map(item -> AIAnalysisContext.EvidenceContext.builder()
                .evidenceItemId(item.getId().toString())
                .type(item.getType().name())
                .summary(item.getSummary())  // already masked by ingestion pipeline
                .level(item.getLevel())
                .timestamp(item.getTimestamp())
                .build())
            .toList();

        return AIAnalysisContext.builder()
            .incidentId(pack.getIncidentId())
            .tenantId(pack.getTenantId())
            .incidentTitle(pack.getTitle())
            .serviceId(pack.getServiceId())
            .severity(pack.getSeverity()  != null ? pack.getSeverity().name()  : null)
            .status(pack.getStatus()      != null ? pack.getStatus().name()    : null)
            .firstSeen(pack.getFirstSeen())
            .lastSeen(pack.getLastSeen())
            .evidenceItems(evidenceContexts)
            .build();
    }

    private AIAnalysisResponse toResponse(AIAnalysis analysis) {
        return AIAnalysisResponse.builder()
            .id(analysis.getId())
            .incidentId(analysis.getIncidentId())
            .tenantId(analysis.getTenantId())
            .summary(analysis.getSummary())
            .likelyCause(analysis.getLikelyCause())
            .confidenceScore(analysis.getConfidenceScore())
            .severity(analysis.getSeverity())
            .evidenceRefs(analysis.getEvidenceRefs())
            .unknowns(analysis.getUnknowns())
            .recommendedNextChecks(analysis.getRecommendedNextChecks())
            .model(analysis.getModel())
            .promptVersion(analysis.getPromptVersion())
            .analysisVersion(analysis.getAnalysisVersion())
            .evidenceCount(analysis.getEvidenceCount())
            .createdAt(analysis.getCreatedAt())
            .build();
    }
}
