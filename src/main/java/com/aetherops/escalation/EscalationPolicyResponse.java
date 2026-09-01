package com.aetherops.escalation;

import com.aetherops.incident.model.IncidentSeverity;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class EscalationPolicyResponse {
    private UUID id;
    private UUID tenantId;
    private String serviceId;
    private IncidentSeverity severityThreshold;
    private int escalateAfterMinutes;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
}
