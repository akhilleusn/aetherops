package com.aetherops.incident.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class IncidentResponse {

    private UUID id;
    private String title;
    private String description;
    private IncidentStatus status;
    private IncidentSeverity severity;
    private String serviceId;
    private String environment;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;
}
