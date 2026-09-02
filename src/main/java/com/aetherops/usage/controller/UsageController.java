package com.aetherops.usage.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import com.aetherops.usage.model.UsageSummaryResponse;
import com.aetherops.usage.service.UsageLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Usage", description = "Tenant usage and AI budget controls")
public class UsageController {

    private final UsageLimitService usageLimitService;

    @GetMapping("/{id}/usage")
    @Operation(summary = "Get monthly usage summary for a tenant. Defaults to the current calendar month.")
    public ApiResponse<UsageSummaryResponse> getUsage(
        @PathVariable UUID id,
        @RequestParam(required = false) String period
    ) {
        TenantContext.requireOwn(id);
        return ApiResponse.success(usageLimitService.getSummary(id, period));
    }
}
