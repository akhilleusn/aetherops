package com.aetherops.service.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateServiceRequest {

    @NotBlank(message = "name is required")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "environment is required")
    @Pattern(
        regexp = "(?i)PRODUCTION|STAGING|DEVELOPMENT",
        message = "environment must be one of: PRODUCTION, STAGING, DEVELOPMENT"
    )
    private String environment;

    @Size(max = 100)
    private String ownerTeam;
}
