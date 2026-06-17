package com.aetherops.analysis.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.analysis.model.AIAnalysisResult;
import com.aetherops.similarity.model.SimilarIncidentContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic AI provider used until a real LLM integration is wired in.
 * Swap by adding another {@link AIProvider} bean marked {@code @Primary}.
 *
 * confidenceScore is a decimal in [0.0, 1.0].
 * Capped at 0.65 because analysis is based solely on log events;
 * metrics, distributed traces, and deployment history are unavailable.
 */
@Service
public class MockAIProvider implements AIProvider {

    private static final String MODEL          = "mock-v1";
    private static final String PROMPT_VERSION = "1.0";

    private static final List<String> UNKNOWNS = List.of(
        "No metrics available — cannot assess resource utilisation or error rates",
        "No distributed traces available — cannot identify upstream or downstream service propagation",
        "No deployment history available — cannot correlate incident with recent changes"
    );

    private static final List<String> SAFE_NEXT_CHECKS = List.of(
        "Review service logs for the affected time window",
        "Check recent deployment history for the affected service",
        "Verify health of dependent services and databases",
        "Inspect connection pool and query latency metrics"
    );

    @Override
    public AIAnalysisResult analyze(AIAnalysisContext context) {
        List<AIAnalysisContext.EvidenceContext> items = context.getEvidenceItems();

        List<String> evidenceRefs = items.stream()
            .map(AIAnalysisContext.EvidenceContext::getEvidenceItemId)
            .toList();

        var similar = context.getSimilarIncidents();
        List<String> nextChecks = similar != null && !similar.isEmpty()
                ? buildNextChecksWithSimilar(similar)
                : SAFE_NEXT_CHECKS;

        return AIAnalysisResult.builder()
            .summary(buildSummary(context, items.size(), similar))
            .likelyCause(buildLikelyCause(items))
            .confidenceScore(computeConfidence(items.size()))
            .evidenceRefs(evidenceRefs)
            .unknowns(UNKNOWNS)
            .recommendedNextChecks(nextChecks)
            .model(MODEL)
            .promptVersion(PROMPT_VERSION)
            .build();
    }

    private String buildSummary(AIAnalysisContext context, int evidenceCount,
                                List<SimilarIncidentContext> similar) {
        String base = String.format(
            "Incident in service '%s': %s — supported by %d log evidence item(s). " +
            "Confidence is limited to available log data only.",
            context.getServiceId(),
            context.getIncidentTitle(),
            evidenceCount
        );
        if (similar == null || similar.isEmpty()) return base;
        return base + String.format(
            " %d similar resolved incident(s) found; resolution patterns may apply.",
            similar.size()
        );
    }

    private List<String> buildNextChecksWithSimilar(List<SimilarIncidentContext> similar) {
        List<String> checks = new ArrayList<>(SAFE_NEXT_CHECKS);
        SimilarIncidentContext best = similar.get(0);
        if (best.getResolutionNotes() != null && !best.getResolutionNotes().isBlank()) {
            checks.add(String.format(
                "Review resolution from similar past incident '%s': %s",
                best.getTitle(), best.getResolutionNotes()
            ));
        } else {
            checks.add(String.format(
                "Review similar past incident '%s' (resolved, similarity %.0f%%)",
                best.getTitle(), best.getSimilarityScore() * 100
            ));
        }
        return List.copyOf(checks);
    }

    private String buildLikelyCause(List<AIAnalysisContext.EvidenceContext> items) {
        if (items.isEmpty()) {
            return "Insufficient evidence to determine a likely cause.";
        }
        // items are ordered newest-first; cite the most recent evidence item
        AIAnalysisContext.EvidenceContext first = items.get(0);
        return "Based on log evidence [" + first.getEvidenceItemId() + "]: " + first.getSummary();
    }

    private double computeConfidence(int evidenceCount) {
        if (evidenceCount == 0) return 0.10;
        if (evidenceCount == 1) return 0.35;
        if (evidenceCount == 2) return 0.45;
        if (evidenceCount == 3) return 0.55;
        return 0.65; // cap: logs only, no metrics/traces/deploys
    }
}
