package com.aetherops.integrations.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigureTicketIntegrationRequest {

    @NotNull(message = "provider is required")
    private TicketProvider provider;

    @NotBlank(message = "baseUrl is required")
    private String baseUrl;

    /** Jira project key, e.g. "OPS". Unused for ServiceNow. */
    private String projectKey;

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "apiToken is required")
    private String apiToken;
}
