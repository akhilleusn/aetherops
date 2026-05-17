package com.aetherops.incident.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateIncidentRequest {

    @NotBlank(message = "title is required")
    @Size(max = 500, message = "title must not exceed 500 characters")
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
