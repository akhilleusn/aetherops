package com.aetherops.evidence.model;

import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class IncidentEvidencePackResponse {

    private UUID incidentId;
    private UUID tenantId;
    private UUID projectId;
    private String title;
    private String serviceId;
    private String environment;
    private IncidentSeverity severity;
    private IncidentStatus status;
    private Instant firstSeen;
    private Instant lastSeen;
    private int totalEvidenceCount;
    private List<EvidenceItemResponse> evidence;
}
