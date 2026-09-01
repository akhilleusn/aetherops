package com.aetherops.integrations.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.model.ConfigureTicketIntegrationRequest;
import com.aetherops.integrations.model.TicketIntegrationResponse;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.service.TicketIntegrationConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ticket-integrations")
@RequiredArgsConstructor
@Tag(name = "Ticket Integrations", description = "Jira and ServiceNow connection configuration")
public class TicketIntegrationConfigController {

    private final TicketIntegrationConfigService configService;

    @PutMapping
    @Operation(summary = "Configure (or reconfigure) a Jira or ServiceNow connection for a tenant")
    public ApiResponse<TicketIntegrationResponse> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody ConfigureTicketIntegrationRequest request
    ) {
        return ApiResponse.success(configService.configure(tenantId, request));
    }

    @GetMapping("/{provider}")
    @Operation(summary = "Get the tenant's connection config for a provider (never includes the API token)")
    public ApiResponse<TicketIntegrationResponse> get(
        @PathVariable UUID tenantId,
        @PathVariable TicketProvider provider
    ) {
        return ApiResponse.success(configService.get(tenantId, provider));
    }

    @DeleteMapping("/{provider}")
    @Operation(summary = "Remove a tenant's ticket integration connection")
    public ApiResponse<Void> delete(@PathVariable UUID tenantId, @PathVariable TicketProvider provider) {
        configService.delete(tenantId, provider);
        return ApiResponse.success(null);
    }
}
