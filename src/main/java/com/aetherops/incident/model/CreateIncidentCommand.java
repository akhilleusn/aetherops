package com.aetherops.incident.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateIncidentCommand {

    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    private UUID projectId;

    @NotBlank(message = "title is required")
    @Size(max = 500)
    private String title;

    private String description;

    @NotNull(message = "severity is required")
    private IncidentSeverity severity;

    @NotBlank(message = "serviceId is required")
    @Size(max = 100)
    private String serviceId;

    @Size(max = 50)
    private String environment;
}
