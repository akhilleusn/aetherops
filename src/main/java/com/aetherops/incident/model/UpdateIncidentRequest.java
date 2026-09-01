package com.aetherops.incident.model;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateIncidentRequest {

    @Size(max = 500)
    private String title;

    private String description;

    private IncidentStatus status;

    private IncidentSeverity severity;

    /** When present, replaces the incident's entire tag map. */
    private Map<String, String> tags;
}
