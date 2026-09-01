package com.aetherops.incident.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class IncidentTemplateResponse {
    private UUID id;
    private UUID tenantId;
    private String name;
    private String defaultTitle;
    private String defaultDescription;
    private IncidentSeverity defaultSeverity;
    private String defaultServiceId;
    private Map<String, String> defaultTags;
    private Instant createdAt;
}
