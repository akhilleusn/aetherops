package com.aetherops.cost;

import com.aetherops.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/cost-settings")
@RequiredArgsConstructor
@Tag(name = "Cost Settings", description = "Per-tenant hourly responder rate used for incident cost estimates")
public class TenantCostSettingsController {

    private final IncidentCostService costService;

    @PutMapping
    @Operation(summary = "Configure the tenant's hourly responder rate")
    public ApiResponse<TenantCostSettingsResponse> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody TenantCostSettingsRequest request
    ) {
        return ApiResponse.success(costService.configureSettings(tenantId, request.getHourlyResponderRateCents()));
    }

    @GetMapping
    @Operation(summary = "Get the tenant's cost settings (defaults are returned when unconfigured)")
    public ApiResponse<TenantCostSettingsResponse> get(@PathVariable UUID tenantId) {
        return ApiResponse.success(costService.getSettings(tenantId));
    }
}
