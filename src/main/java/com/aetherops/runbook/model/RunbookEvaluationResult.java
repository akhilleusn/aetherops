package com.aetherops.runbook.model;

import com.aetherops.analysis.model.RiskLevel;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class RunbookEvaluationResult {

    private boolean passed;
    private List<String> issues;
    private RiskLevel riskLevel;
    private Instant checkedAt;
    private int checksPerformed;
}
