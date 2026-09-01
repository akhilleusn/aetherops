package com.aetherops.incident.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateIncidentRequest {

    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    private UUID projectId;

    /** Optional when {@link #templateId} supplies a default title. */
    @Size(max = 500, message = "title must not exceed 500 characters")
    private String title;

    private String description;

    /** Optional — auto-detected from title/description when omitted, or filled from the template. */
    private IncidentSeverity severity;

    /** Optional when {@link #templateId} supplies a default serviceId. */
    @Size(max = 100)
    private String serviceId;

    @Size(max = 50)
    private String environment;

    /** Free-form key/value tags for this incident, e.g. {"team": "payments", "region": "us-east"}. */
    private Map<String, String> tags;

    /** When set, defaults from this template fill in any field left null above. */
    private UUID templateId;
}
