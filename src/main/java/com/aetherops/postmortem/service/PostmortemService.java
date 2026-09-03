package com.aetherops.postmortem.service;

import com.aetherops.analysis.model.AIAnalysis;
import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.repository.AIAnalysisRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.service.EvidencePackService;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.postmortem.model.*;
import com.aetherops.postmortem.repository.PostmortemDraftRepository;
import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.slack.SlackConversationService;
import com.aetherops.slack.SlackWorkspace;
import com.aetherops.slack.SlackWorkspaceRepository;
import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.service.UsageLimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostmortemService {

    private final EvidencePackService evidencePackService;
    private final AIAnalysisRepository aiAnalysisRepository;
    private final PostmortemDraftGenerator draftGenerator;
    private final PostmortemEvaluationService evaluationService;
    private final PostmortemDraftRepository postmortemDraftRepository;
    private final UsageLimitService usageLimitService;
    private final AuditLogService auditLogService;
    private final IncidentRepository incidentRepository;
    private final SlackWorkspaceRepository slackWorkspaceRepository;
    private final SlackConversationService slackConversationService;

    /**
     * Each call creates a new PostmortemDraft row (append-only).
     * Rationale: evidence and AI analysis may grow between calls;
     * each draft reflects exactly what was available at generation time.
     */
    @Transactional
    public PostmortemDraftResponse generate(UUID incidentId, UUID tenantId) {
        usageLimitService.checkAndIncrement(tenantId, UsageType.POSTMORTEM_GENERATION);
        IncidentEvidencePackResponse pack = evidencePackService.getEvidencePack(incidentId, tenantId);
        AIAnalysisContext incidentContext = buildContext(pack);

        Optional<AIAnalysis> latestAnalysis = aiAnalysisRepository
            .findTopByIncidentIdOrderByCreatedAtDesc(incidentId);

        PostmortemContext context = buildPostmortemContext(incidentContext, latestAnalysis, incidentId, tenantId);
        PostmortemDraftResult result = draftGenerator.generate(context);

        int version = (int) postmortemDraftRepository.countByIncidentId(incidentId) + 1;

        PostmortemDraft saved = postmortemDraftRepository.saveAndFlush(PostmortemDraft.builder()
            .incidentId(incidentId)
            .tenantId(tenantId)
            .draftVersion(version)
            .evidenceCount(pack.getTotalEvidenceCount())
            .analysisEnriched(latestAnalysis.isPresent())
            .incidentSummary(result.getIncidentSummary())
            .timeline(result.getTimeline())
            .likelyCause(result.getLikelyCause())
            .impact(result.getImpact())
            .dataGaps(result.getDataGaps())
            .followUpActions(result.getFollowUpActions())
            .generatorVersion(result.getGeneratorVersion())
            .slackDiscussionSummary(result.getSlackDiscussionSummary())
            .build());

        log.info("Created postmortem draft id={} incidentId={} version={} analysisEnriched={}",
            saved.getId(), incidentId, version, saved.isAnalysisEnriched());

        PostmortemDraftResponse response = toResponse(saved);

        auditLogService.record(
            tenantId, AuditAction.POSTMORTEM_GENERATED, AuditStatus.SUCCESS,
            "INCIDENT", incidentId,
            "Postmortem draft v" + saved.getDraftVersion() + " generated for incident " + incidentId
        );

        PostmortemEvaluationResult evaluation = evaluationService.evaluate(response, pack);
        if (!evaluation.isPassed()) {
            log.warn("Postmortem id={} failed evaluation: riskLevel={} issues=[{}]",
                saved.getId(), evaluation.getRiskLevel(),
                String.join("; ", evaluation.getIssues()));
        } else {
            log.debug("Postmortem id={} passed all {} evaluation checks",
                saved.getId(), evaluation.getChecksPerformed());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public PostmortemDraftResponse getLatest(UUID incidentId, UUID tenantId) {
        return postmortemDraftRepository
            .findTopByIncidentIdAndTenantIdOrderByCreatedAtDesc(incidentId, tenantId)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException(
                "No postmortem draft found for incident " + incidentId));
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

    private PostmortemContext buildPostmortemContext(
        AIAnalysisContext incidentContext, Optional<AIAnalysis> latestAnalysis, UUID incidentId, UUID tenantId
    ) {
        PostmortemContext.PostmortemContextBuilder builder = PostmortemContext.builder()
            .incidentContext(incidentContext);

        latestAnalysis.ifPresentOrElse(
            analysis -> builder
                .hasAnalysis(true)
                .analysedLikelyCause(analysis.getLikelyCause())
                .analysedUnknowns(analysis.getUnknowns())
                .analysedConfidenceScore(analysis.getConfidenceScore()),
            () -> builder
                .hasAnalysis(false)
                .analysedLikelyCause(null)
                .analysedUnknowns(List.of())
                .analysedConfidenceScore(0.0)
        );

        builder.slackTranscript(fetchSlackTranscript(incidentId, tenantId));

        return builder.build();
    }

    /** Best-effort enrichment — never lets a Slack failure block postmortem generation. */
    private List<String> fetchSlackTranscript(UUID incidentId, UUID tenantId) {
        try {
            Incident incident = incidentRepository.findById(incidentId).orElse(null);
            if (incident == null || incident.getSlackChannelId() == null || incident.getSlackThreadTs() == null) {
                return List.of();
            }
            Optional<SlackWorkspace> workspace = slackWorkspaceRepository.findByTenantId(tenantId);
            if (workspace.isEmpty()) {
                return List.of();
            }
            return slackConversationService.fetchThreadTranscript(
                workspace.get(), incident.getSlackChannelId(), incident.getSlackThreadTs());
        } catch (Exception e) {
            log.warn("Failed to fetch Slack transcript for incidentId={}", incidentId, e);
            return List.of();
        }
    }

    private PostmortemDraftResponse toResponse(PostmortemDraft draft) {
        return PostmortemDraftResponse.builder()
            .id(draft.getId())
            .incidentId(draft.getIncidentId())
            .tenantId(draft.getTenantId())
            .draftVersion(draft.getDraftVersion())
            .evidenceCount(draft.getEvidenceCount())
            .analysisEnriched(draft.isAnalysisEnriched())
            .incidentSummary(draft.getIncidentSummary())
            .timeline(draft.getTimeline())
            .likelyCause(draft.getLikelyCause())
            .impact(draft.getImpact())
            .dataGaps(draft.getDataGaps())
            .followUpActions(draft.getFollowUpActions())
            .generatorVersion(draft.getGeneratorVersion())
            .slackDiscussionSummary(draft.getSlackDiscussionSummary())
            .createdAt(draft.getCreatedAt())
            .build();
    }
}
