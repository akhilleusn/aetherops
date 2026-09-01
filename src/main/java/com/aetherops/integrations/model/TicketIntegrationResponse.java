package com.aetherops.integrations.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Never carries the API token — see TicketIntegrationConfigController. */
@Data
@Builder
public class TicketIntegrationResponse {
    private UUID tenantId;
    private TicketProvider provider;
    private String baseUrl;
    private String projectKey;
    private String username;
    private boolean enabled;
    private Instant updatedAt;
}
