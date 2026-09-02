package com.aetherops.analytics;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "MTTR, MTBF, incident trends, and team performance")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/dashboard")
    @Operation(summary = "Get the analytics dashboard for a date range. Defaults to the last 30 days.")
    public ApiResponse<AnalyticsDashboardResponse> getDashboard(
        @PathVariable UUID tenantId,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to
    ) {
        TenantContext.requireOwn(tenantId);
        Instant effectiveTo = to != null ? to : Instant.now();
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(30, ChronoUnit.DAYS);
        return ApiResponse.success(analyticsService.getDashboard(tenantId, effectiveFrom, effectiveTo));
    }
}
