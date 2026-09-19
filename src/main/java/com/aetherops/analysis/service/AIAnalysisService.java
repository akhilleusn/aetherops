package com.aetherops.analysis.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import com.aetherops.analysis.model.*;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.service.UsageLimitService;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.exception.UsageLimitExceededException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import com.aetherops.similarity.model.SimilarIncidentContext;
import com.aetherops.similarity.service.IncidentEmbeddingService;
import com.aetherops.usage.UsageBillingService;
import com.aetherops.config.FeatureFlag;
import com.aetherops.config.FeatureFlagService;


@Service
@RequiredArgsConstructor
@Slf4j
public class AIAnalysisService {

    private final EvidencePackService evidencePackService;
    private final AIProvider aiProvider;
    private final AIAnalysisRepository aiAnalysisRepository;
    private final AnalysisEvaluationService evaluationService;
    private final UsageLimitService usageLimitService;
    private final AuditLogService auditLogService;
    private final MeterRegistry meterRegistry;
    private final UsageBillingService usageBillingService;
    private final FeatureFlagService featureFlagService;
    private final IncidentEmbeddingService incidentEmbeddingService;
    private final FailedAnalysisRecorder failedAnalysisRecorder;

    @Value("${analysis.max-evidence-summary-chars}")
    private int maxEvidenceSummaryChars;


    @CircuitBreaker(name = "anthropic-ai", fallbackMethod = "analyzeFallback")
    @Bulkhead(name = "anthropic-ai")
    @Retry(name = "anthropic-ai", fallbackMethod = "analyzeFallback")
    @Transactional
    public AIAnalysisResponse analyze(UUID incidentId, UUID tenantId) {
        if (!featureFlagService.isEnabled(FeatureFlag.AI_ANALYSIS, tenantId)) {
            log.info("AI_ANALYSIS feature flag disabled for tenantId={}", tenantId);
            return AIAnalysisResponse.builder()
                    .incidentId(incidentId)
                    .tenantId(tenantId)
                    .summary("AI analysis is currently disabled for this tenant.")
                    .likelyCause("Feature disabled.")
                    .confidenceScore(0.0)
                    .build();
        }
        usageLimitService.checkAndIncrement(tenantId, UsageType.AI_ANALYSIS);
        IncidentEvidencePackResponse pack = evidencePackService.getEvidencePack(incidentId, tenantId);

        List<SimilarIncidentContext> similarIncidents =
                incidentEmbeddingService.findSimilar(incidentId, tenantId);
        if (!similarIncidents.isEmpty()) {
            log.info("Found {} similar incident(s) for RAG context incidentId={}",
                    similarIncidents.size(), incidentId);
        }

        AIAnalysisContext context = buildContext(pack, similarIncidents);
        AIAnalysisResult result   = aiProvider.analyze(context);

        int version = (int) aiAnalysisRepository.countByIncidentId(incidentId) + 1;

        List<String> similarIncidentIds = similarIncidents.stream()
                .map(s -> s.getIncidentId().toString())
                .toList();

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
                .similarIncidentIds(similarIncidentIds.isEmpty() ? null : similarIncidentIds)
                .build());

        log.info("Created AI analysis id={} incidentId={} confidence={} evidenceCount={}",
                saved.getId(), incidentId, saved.getConfidenceScore(), pack.getTotalEvidenceCount());

        Counter.builder("aetherops.ai.analysis.requests")
                .description("Number of AI analysis requests per tenant")
                .tag("tenantId", tenantId.toString())
                .register(meterRegistry)
                .increment();
                 usageBillingService.record(tenantId, "AI_ANALYSIS", 1);
        AIAnalysisResponse response = toResponse(saved).toBuilder()
                .similarIncidents(similarIncidents)
                .build();

        auditLogService.record(
                tenantId, AuditAction.ANALYSIS_GENERATED, AuditStatus.SUCCESS,
                "INCIDENT", incidentId,
                "AI analysis v" + saved.getAnalysisVersion() + " generated for incident " + incidentId
        );

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

    public AIAnalysisResponse analyzeFallback(UUID incidentId, UUID tenantId, Exception ex) {
        if (ex instanceof UsageLimitExceededException ule) {
            throw ule;
        }
        if (ex instanceof ResourceNotFoundException rne) {
            throw rne;
        }
        log.warn("Circuit breaker open for AI analysis incidentId={} reason={}",
                incidentId, ex.getMessage());
        failedAnalysisRecorder.record(incidentId, tenantId, ex.getMessage());
        return AIAnalysisResponse.builder()
                .incidentId(incidentId)
                .tenantId(tenantId)
                .status(AnalysisStatus.FAILED)
                .errorMessage(ex.getMessage())
                .summary("AI analysis temporarily unavailable. Please try again later.")
                .likelyCause("Analysis service is currently unavailable.")
                .confidenceScore(0.0)
                .build();
    }

    @Transactional(readOnly = true)
    public AIAnalysisResponse getLatest(UUID incidentId, UUID tenantId) {
        return aiAnalysisRepository
                .findTopByIncidentIdOrderByCreatedAtDesc(incidentId)
                .filter(a -> tenantId.equals(a.getTenantId()))
                .map(this::toResponse)
                .orElseThrow(() -> new com.aetherops.common.exception.ResourceNotFoundException(
                        "No analysis found for incident " + incidentId));
    }

    private AIAnalysisContext buildContext(IncidentEvidencePackResponse pack,
                                          List<SimilarIncidentContext> similarIncidents) {
        List<AIAnalysisContext.EvidenceContext> evidenceContexts = pack.getEvidence().stream()
                .map(item -> AIAnalysisContext.EvidenceContext.builder()
                        .evidenceItemId(item.getId().toString())
                        .type(item.getType().name())
                        .summary(truncate(item.getSummary()))
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
                .similarIncidents(similarIncidents)
                .build();
    }

    /**
     * Caps evidence text before it's sent to any analysis provider. Configurable
     * (analysis.max-evidence-summary-chars) so this can be tightened once a real, billed
     * provider is enabled -- there's currently no upstream limit on log message size.
     */
    private String truncate(String summary) {
        if (summary == null || summary.length() <= maxEvidenceSummaryChars) {
            return summary;
        }
        return summary.substring(0, maxEvidenceSummaryChars) + "…[truncated]";
    }

    private AIAnalysisResponse toResponse(AIAnalysis analysis) {
        return AIAnalysisResponse.builder()
                .id(analysis.getId())
                .incidentId(analysis.getIncidentId())
                .tenantId(analysis.getTenantId())
                .status(analysis.getStatus())
                .errorMessage(analysis.getErrorMessage())
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
                .similarIncidentIds(analysis.getSimilarIncidentIds())
                .build();
    }
}