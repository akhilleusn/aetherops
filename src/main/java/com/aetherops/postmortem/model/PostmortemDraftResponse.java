package com.aetherops.postmortem.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
public class PostmortemDraftResponse {

    private UUID id;
    private UUID incidentId;
    private UUID tenantId;
    /** Which generation run this is for the incident. First run = 1. */
    private int draftVersion;
    /** Number of evidence items available at generation time. */
    private int evidenceCount;
    /** True if a prior AI analysis was used to enrich likelyCause and dataGaps. */
    private boolean analysisEnriched;
    private String incidentSummary;
    private List<TimelineEntry> timeline;
    private String likelyCause;
    private String impact;
    private List<String> dataGaps;
    private List<String> followUpActions;
    private String generatorVersion;
    private String slackDiscussionSummary;
    private Instant createdAt;
}
