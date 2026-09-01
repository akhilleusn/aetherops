package com.aetherops.incident.severity;

import com.aetherops.incident.model.IncidentSeverity;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SeverityDetectionResult {
    private IncidentSeverity predictedSeverity;
    private double confidenceScore;
    private String reasoning;
}
