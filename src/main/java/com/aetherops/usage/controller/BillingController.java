package com.aetherops.usage.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.usage.service.BillingPeriodService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/billing")
@RequiredArgsConstructor
@Tag(name = "Billing", description = "Billing period usage and projections")
public class BillingController {

    private final BillingPeriodService billingPeriodService;

    @GetMapping("/current")
    @Operation(summary = "Get usage totals for the current billing period")
    public ApiResponse<Map<String, Object>> getCurrentPeriod(@PathVariable UUID tenantId) {
        return ApiResponse.success(billingPeriodService.getCurrentPeriodUsage(tenantId));
    }

    @GetMapping("/history")
    @Operation(summary = "Get monthly usage aggregates for the last N months")
    public ApiResponse<List<Map<String, Object>>> getHistory(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "6") int months
    ) {
        return ApiResponse.success(billingPeriodService.getUsageHistory(tenantId, months));
    }

    @GetMapping("/projected")
    @Operation(summary = "Get linear projection of usage for the current month")
    public ApiResponse<Map<String, Object>> getProjected(@PathVariable UUID tenantId) {
        return ApiResponse.success(billingPeriodService.getProjectedUsage(tenantId));
    }
}
