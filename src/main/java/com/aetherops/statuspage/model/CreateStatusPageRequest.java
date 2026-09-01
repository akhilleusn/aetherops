package com.aetherops.statuspage.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateStatusPageRequest {

    @NotBlank(message = "name is required")
    @Size(max = 200)
    private String name;

    @NotBlank(message = "slug is required")
    @Size(max = 63)
    @Pattern(regexp = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", message = "slug must be lowercase alphanumeric with hyphens")
    private String slug;
}
