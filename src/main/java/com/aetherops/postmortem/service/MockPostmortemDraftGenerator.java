package com.aetherops.postmortem.service;

import com.aetherops.analysis.model.AIAnalysisContext;
import com.aetherops.postmortem.model.PostmortemContext;
import com.aetherops.postmortem.model.PostmortemDraftResult;
import com.aetherops.postmortem.model.TimelineEntry;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deterministic postmortem generator used until a real LLM integration is wired in.
 * Swap by adding another {@link PostmortemDraftGenerator} bean marked {@code @Primary}.
 *
 * When a prior AI analysis exists for the incident it is used to enrich
 * likelyCause and dataGaps. If no analysis exists, both fall back to
 * evidence-derived content. All follow-up actions are read-only and non-destructive.
 */
@Service
public class MockPostmortemDraftGenerator implements PostmortemDraftGenerator {

    private static final String GENERATOR_VERSION = "mock-v1";

    private static final List<String> LOG_ONLY_DATA_GAPS = List.of(
        "No metrics available — cannot assess resource utilisation or error rates during the incident window.",
        "No distributed traces available — cannot determine upstream or downstream service propagation.",
        "No deployment history available — cannot correlate the incident with recent code or configuration changes.",
        "Root cause has not been confirmed — further manual investigation is required."
    );

    @Override
    public PostmortemDraftResult generate(PostmortemContext context) {
        AIAnalysisContext ctx = context.getIncidentContext();
        List<AIAnalysisContext.EvidenceContext> evidence = ctx.getEvidenceItems();

        return PostmortemDraftResult.builder()
            .incidentSummary(buildIncidentSummary(ctx, evidence.size(), context.isHasAnalysis()))
            .timeline(buildTimeline(ctx, evidence))
            .likelyCause(buildLikelyCause(ctx, evidence, context))
            .impact(buildImpact(ctx, evidence.size()))
            .dataGaps(buildDataGaps(context))
            .followUpActions(buildFollowUpActions(ctx, evidence))
            .generatorVersion(GENERATOR_VERSION)
            .build();
    }

    private String buildIncidentSummary(AIAnalysisContext ctx, int evidenceCount, boolean hasAnalysis) {
        String service  = ctx.getServiceId() != null ? ctx.getServiceId() : "an unknown service";
        String severity = ctx.getSeverity() != null ? ctx.getSeverity() : "UNKNOWN";
        String status   = ctx.getStatus()   != null ? ctx.getStatus()   : "UNKNOWN";
        String window   = formatTimeWindow(ctx.getFirstSeen(), ctx.getLastSeen());
        String basis    = hasAnalysis ? "log evidence and AI analysis" : "available log evidence only";

        return String.format(
            "An incident was detected in service '%s' %s. " +
            "Incident title: \"%s\". Severity: %s. Status at time of draft: %s. " +
            "%d log evidence item(s) were recorded during the incident window. " +
            "This postmortem draft was generated from %s and should be reviewed and completed by the incident owner.",
            service, window, ctx.getIncidentTitle(), severity, status, evidenceCount, basis
        );
    }

    private List<TimelineEntry> buildTimeline(
        AIAnalysisContext ctx, List<AIAnalysisContext.EvidenceContext> evidence
    ) {
        List<TimelineEntry> entries = new ArrayList<>();

        entries.add(TimelineEntry.builder()
            .timestamp(ctx.getFirstSeen())
            .description("Incident first detected in " + ctx.getServiceId() + ".")
            .build());

        // Evidence items sorted chronologically
        evidence.stream()
            .sorted(Comparator.comparing(AIAnalysisContext.EvidenceContext::getTimestamp))
            .forEach(item -> entries.add(TimelineEntry.builder()
                .timestamp(item.getTimestamp())
                .description(item.getLevel() + " log event recorded: \"" + item.getSummary() + "\".")
                .evidenceRef(item.getEvidenceItemId())
                .build()));

        if (ctx.getLastSeen() != null
                && !ctx.getLastSeen().equals(ctx.getFirstSeen())) {
            entries.add(TimelineEntry.builder()
                .timestamp(ctx.getLastSeen())
                .description("Most recent evidence recorded for this incident.")
                .build());
        }

        return entries;
    }

    private String buildLikelyCause(
        AIAnalysisContext ctx,
        List<AIAnalysisContext.EvidenceContext> evidence,
        PostmortemContext context
    ) {
        if (context.isHasAnalysis()
                && context.getAnalysedLikelyCause() != null
                && !context.getAnalysedLikelyCause().isBlank()) {
            return context.getAnalysedLikelyCause()
                + " (Confidence: " + String.format("%.0f%%", context.getAnalysedConfidenceScore() * 100)
                + " — derived from AI analysis of available log evidence.)";
        }
        if (!evidence.isEmpty()) {
            AIAnalysisContext.EvidenceContext latest = evidence.stream()
                .max(Comparator.comparing(AIAnalysisContext.EvidenceContext::getTimestamp))
                .orElseThrow();
            return "Based on available log evidence, the most recent "
                + latest.getLevel() + "-level event was: \""
                + latest.getSummary() + "\" (evidence item " + latest.getEvidenceItemId() + "). "
                + "Root cause has not been confirmed — further investigation is required.";
        }
        return "Insufficient evidence to determine a likely cause at the time of this draft. "
            + "Manual investigation is required before this section can be completed.";
    }

    private String buildImpact(AIAnalysisContext ctx, int evidenceCount) {
        String service   = ctx.getServiceId() != null ? ctx.getServiceId() : "the affected service";
        String severity  = ctx.getSeverity() != null ? ctx.getSeverity() : "UNKNOWN";
        String duration  = formatDuration(ctx.getFirstSeen(), ctx.getLastSeen());

        return String.format(
            "Service '%s' experienced errors for %s (from %s to %s). " +
            "%d log event(s) were recorded as evidence. Incident severity: %s. " +
            "Customer-facing impact scope has not been confirmed and should be assessed by the incident owner.",
            service, duration, ctx.getFirstSeen(), ctx.getLastSeen(),
            evidenceCount, severity
        );
    }

    private List<String> buildDataGaps(PostmortemContext context) {
        if (context.isHasAnalysis()
                && context.getAnalysedUnknowns() != null
                && !context.getAnalysedUnknowns().isEmpty()) {
            return context.getAnalysedUnknowns();
        }
        return LOG_ONLY_DATA_GAPS;
    }

    private List<String> buildFollowUpActions(
        AIAnalysisContext ctx, List<AIAnalysisContext.EvidenceContext> evidence
    ) {
        String service = ctx.getServiceId() != null ? ctx.getServiceId() : "the affected service";
        List<String> actions = new ArrayList<>();

        actions.add("Review and document the confirmed root cause once the investigation is complete.");
        actions.add("Add a metric alert for elevated error rates in '"
            + service + "' to detect this class of incident earlier in future.");
        actions.add("Review error handling and retry behaviour in '"
            + service + "' for the code path implicated in this incident.");
        actions.add("Document the dependency chain for '"
            + service + "' so responders can quickly identify blast radius in future incidents.");
        actions.add("Consider adding structured error logging to '"
            + service + "' to improve evidence quality for future incident investigations.");
        actions.add("Schedule a team retrospective to review this incident, "
            + "the investigation timeline, and opportunities to improve the on-call response process.");

        return actions;
    }

    private String formatTimeWindow(Instant firstSeen, Instant lastSeen) {
        if (firstSeen == null) return "";
        if (lastSeen == null || lastSeen.equals(firstSeen)) {
            return "at " + firstSeen;
        }
        return "between " + firstSeen + " and " + lastSeen;
    }

    private String formatDuration(Instant firstSeen, Instant lastSeen) {
        if (firstSeen == null || lastSeen == null) return "an unknown duration";
        long minutes = Duration.between(firstSeen, lastSeen).toMinutes();
        if (minutes < 1)  return "less than 1 minute";
        if (minutes < 60) return minutes + " minute(s)";
        long hours = minutes / 60;
        long rem   = minutes % 60;
        return rem == 0
            ? hours + " hour(s)"
            : hours + " hour(s) " + rem + " minute(s)";
    }
}
