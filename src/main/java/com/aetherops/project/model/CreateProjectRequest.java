package com.aetherops.project.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateProjectRequest {

    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    @NotBlank(message = "name is required")
    @Size(max = 255)
    private String name;
}
