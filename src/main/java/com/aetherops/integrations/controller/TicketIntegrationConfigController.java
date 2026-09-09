package com.aetherops.integrations.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.model.ConfigureTicketIntegrationRequest;
import com.aetherops.integrations.model.TicketIntegrationResponse;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.service.TicketIntegrationConfigService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ticket-integrations")
@RequiredArgsConstructor
@Tag(name = "Ticket Integrations", description = "Jira and ServiceNow connection configuration")
public class TicketIntegrationConfigController {

    private final TicketIntegrationConfigService configService;

    @PutMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Configure (or reconfigure) a Jira or ServiceNow connection for a tenant")
    public ApiResponse<TicketIntegrationResponse> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody ConfigureTicketIntegrationRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(configService.configure(tenantId, request));
    }

    @GetMapping("/{provider}")
    @Operation(summary = "Get the tenant's connection config for a provider (never includes the API token)")
    public ApiResponse<TicketIntegrationResponse> get(
        @PathVariable UUID tenantId,
        @PathVariable TicketProvider provider
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(configService.get(tenantId, provider));
    }

    @DeleteMapping("/{provider}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Remove a tenant's ticket integration connection")
    public ApiResponse<Void> delete(@PathVariable UUID tenantId, @PathVariable TicketProvider provider) {
        TenantContext.requireOwn(tenantId);
        configService.delete(tenantId, provider);
        return ApiResponse.success(null);
    }
}
