package com.aetherops.analysis.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class AnalysisEvaluationResult {

    private boolean passed;
    private List<String> issues;
    private RiskLevel riskLevel;
    private Instant checkedAt;
    private int checksPerformed;
}
