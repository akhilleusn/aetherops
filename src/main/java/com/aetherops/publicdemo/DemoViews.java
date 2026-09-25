package com.aetherops.publicdemo;

import com.aetherops.analysis.model.AIAnalysisResponse;
import com.aetherops.incident.model.IncidentResponse;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineResponse;
import com.aetherops.oncall.SeverityLevel;
import com.aetherops.postmortem.model.PostmortemDraftResponse;
import com.aetherops.postmortem.model.TimelineEntry;
import com.aetherops.runbook.model.RunbookStep;
import com.aetherops.runbook.model.RunbookSuggestionResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The only shapes the public demo serves: explicit allow-lists. Deliberately absent everywhere:
 * tenantId, projectId, fingerprint, latestEventId, tags, description, resolutionNotes, Slack
 * channel/thread ids, errorMessage, similarIncidents, slackDiscussionSummary, rawPayload,
 * traceId/spanId, and engineer or Slack identities in timeline details.
 */
public final class DemoViews {

    private DemoViews() {}

    public record Incident(UUID id, String title, IncidentStatus status, IncidentSeverity severity,
                           String serviceId, String environment, int eventCount,
                           Instant firstSeenAt, Instant lastSeenAt, Instant resolvedAt) {
        static Incident from(IncidentResponse r) {
            return new Incident(r.getId(), r.getTitle(), r.getStatus(), r.getSeverity(), r.getServiceId(),
                r.getEnvironment(), r.getEventCount(), r.getFirstSeenAt(), r.getLastSeenAt(), r.getResolvedAt());
        }
    }

    public record TimelineEvent(IncidentTimeline.TimelineEventType eventType, SeverityLevel severity,
                                String detail, Instant createdAt) {
        // Page entries name the engineer or the failing channel -- replaced with a fixed label.
        static TimelineEvent from(IncidentTimelineResponse e) {
            String detail = switch (e.getEventType()) {
                case ENGINEER_PAGED -> "On-call engineer paged";
                case PAGE_FAILED    -> "Paging failed";
                default             -> e.getDetail();
            };
            return new TimelineEvent(e.getEventType(), e.getSeverity(), detail, e.getCreatedAt());
        }
    }

    public record LogLine(Instant occurredAt, LogLevel level, String serviceId, String environment, String message) {
        static LogLine from(LogEvent e) {
            return new LogLine(e.getOccurredAt(), e.getLevel(), e.getServiceId(), e.getEnvironment(), e.getMessage());
        }
    }

    public record Analysis(int analysisVersion, String summary, String likelyCause, double confidenceScore,
                           List<String> evidenceRefs, List<String> unknowns, List<String> recommendedNextChecks,
                           String model, Instant createdAt) {
        static Analysis from(AIAnalysisResponse a) {
            return new Analysis(a.getAnalysisVersion(), a.getSummary(), a.getLikelyCause(), a.getConfidenceScore(),
                a.getEvidenceRefs(), a.getUnknowns(), a.getRecommendedNextChecks(), a.getModel(), a.getCreatedAt());
        }
    }

    public record Runbook(int runbookVersion, List<RunbookStep> steps, String generationBasis, Instant createdAt) {
        static Runbook from(RunbookSuggestionResponse r) {
            return new Runbook(r.getRunbookVersion(), r.getSteps(), r.getGenerationBasis(), r.getCreatedAt());
        }
    }

    public record Postmortem(int draftVersion, String incidentSummary, List<TimelineEntry> timeline,
                             String likelyCause, String impact, List<String> dataGaps,
                             List<String> followUpActions, Instant createdAt) {
        static Postmortem from(PostmortemDraftResponse p) {
            return new Postmortem(p.getDraftVersion(), p.getIncidentSummary(), p.getTimeline(), p.getLikelyCause(),
                p.getImpact(), p.getDataGaps(), p.getFollowUpActions(), p.getCreatedAt());
        }
    }
}
