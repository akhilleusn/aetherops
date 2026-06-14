package com.aetherops.incident.model;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class ResolveIncidentCommand {

    @NotNull(message = "incidentId is required")
    private UUID incidentId;

    @NotNull(message = "tenantId is required")
    private UUID tenantId;
}
