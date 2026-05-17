package com.aetherops.incident.model;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateIncidentRequest {

    @Size(max = 500)
    private String title;

    private String description;

    private IncidentStatus status;

    private IncidentSeverity severity;
}
