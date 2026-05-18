package com.aetherops.analysis.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AIAnalysisResponse {

    private UUID id;
    private UUID incidentId;
    private UUID tenantId;
    private String summary;
    private String likelyCause;
    /** Decimal 0.0–1.0. Example: 0.35 = 35% confidence. */
    private double confidenceScore;
    private String severity;
    private List<String> evidenceRefs;
    private List<String> unknowns;
    private List<String> recommendedNextChecks;
    private String model;
    private String promptVersion;
    private Instant createdAt;
}
