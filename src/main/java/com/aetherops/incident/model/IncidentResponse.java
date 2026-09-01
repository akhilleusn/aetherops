package com.aetherops.incident.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class IncidentResponse {

    private UUID id;
    private UUID tenantId;
    private UUID projectId;
    private String title;
    private String description;
    private IncidentStatus status;
    private IncidentSeverity severity;
    private String serviceId;
    private String environment;
    private String fingerprint;
    private int eventCount;
    private Instant firstSeenAt;
    private Instant lastSeenAt;
    private UUID latestEventId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
    private Map<String, String> tags;
}
