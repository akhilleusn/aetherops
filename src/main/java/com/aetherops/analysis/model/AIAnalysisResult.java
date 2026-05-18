package com.aetherops.analysis.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AIAnalysisResult {

    private String summary;
    private String likelyCause;
    private double confidenceScore;   // decimal 0.0–1.0
    private List<String> evidenceRefs;
    private List<String> unknowns;
    private List<String> recommendedNextChecks;
    private String model;
    private String promptVersion;
}
