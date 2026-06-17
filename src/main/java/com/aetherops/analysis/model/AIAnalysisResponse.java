package com.aetherops.analysis.model;

import com.aetherops.similarity.model.SimilarIncidentContext;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
public class AIAnalysisResponse {

    private UUID id;
    private UUID incidentId;
    private UUID tenantId;
    private String summary;
    private String likelyCause;
    /** Decimal 0.0–1.0. Example: 0.35 = 35% confidence. */
    private double confidenceScore;
    private String severity;
    /** Which analysis run this is for the incident. First run = 1, increments on each re-analysis. */
    private int analysisVersion;
    /** Number of evidence items the analysis was based on. */
    private int evidenceCount;
    private List<String> evidenceRefs;
    private List<String> unknowns;
    private List<String> recommendedNextChecks;
    private String model;
    private String promptVersion;
    private Instant createdAt;
    /** IDs of resolved incidents used as RAG context during analysis (stored). */
    private List<String> similarIncidentIds;
    /** Full context of similar incidents — populated on fresh analyze(), empty on getLatest(). */
    private List<SimilarIncidentContext> similarIncidents;
}
