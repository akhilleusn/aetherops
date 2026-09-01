package com.aetherops.escalation;

import com.aetherops.incident.model.IncidentSeverity;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EscalationPolicyRequest {

    /** Null configures the tenant-wide default policy rather than a per-service override. */
    private String serviceId;

    /** Defaults to CRITICAL server-side when null. */
    private IncidentSeverity severityThreshold;

    @Positive
    private Integer escalateAfterMinutes;

    /** Defaults to true server-side when null. */
    private Boolean enabled;
}
