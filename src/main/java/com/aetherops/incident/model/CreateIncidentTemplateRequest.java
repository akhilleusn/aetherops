package com.aetherops.incident.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateIncidentTemplateRequest {

    @NotBlank(message = "name is required")
    @Size(max = 100)
    private String name;

    @Size(max = 500)
    private String defaultTitle;

    private String defaultDescription;

    private IncidentSeverity defaultSeverity;

    @Size(max = 100)
    private String defaultServiceId;

    private Map<String, String> defaultTags;
}
