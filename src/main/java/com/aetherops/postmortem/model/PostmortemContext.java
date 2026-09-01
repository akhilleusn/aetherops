package com.aetherops.postmortem.model;

import com.aetherops.analysis.model.AIAnalysisContext;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PostmortemContext {

    /** Incident metadata and evidence items — always present. */
    private AIAnalysisContext incidentContext;

    /**
     * Enrichment fields populated when an AI analysis exists for the incident.
     * When {@code hasAnalysis} is false all other analysis fields are null / empty.
     */
    private boolean hasAnalysis;
    private String  analysedLikelyCause;
    private List<String> analysedUnknowns;
    private double  analysedConfidenceScore;

    /** Slack thread message text, chronological. Empty when no thread was linked or the fetch failed. */
    @Builder.Default
    private List<String> slackTranscript = List.of();

    public boolean hasSlackTranscript() {
        return slackTranscript != null && !slackTranscript.isEmpty();
    }
}
