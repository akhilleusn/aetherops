package com.aetherops.cost;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/incidents/{incidentId}/cost")
@RequiredArgsConstructor
@Tag(name = "Incident Cost Tracking", description = "Log and estimate the spend incurred during an incident")
public class IncidentCostController {

    private final IncidentCostService costService;

    @PostMapping("/entries")
    @Operation(summary = "Log a manual cost line-item against an incident")
    public ApiResponse<CostEntryResponse> addEntry(
        @PathVariable UUID tenantId,
        @PathVariable UUID incidentId,
        @Valid @RequestBody AddCostEntryRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(costService.addEntry(
            tenantId, incidentId, request.getDescription(), request.getAmountCents(),
            request.getCurrency(), request.getRecordedBy()));
    }

    @GetMapping
    @Operation(summary = "Get the full cost summary (all entries + total) for an incident")
    public ApiResponse<CostSummaryResponse> getSummary(
        @PathVariable UUID tenantId,
        @PathVariable UUID incidentId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(costService.getSummary(tenantId, incidentId));
    }

    @GetMapping("/estimate")
    @Operation(summary = "Preview the auto-estimated responder-time cost for an incident (does not persist)")
    public ApiResponse<Map<String, Long>> getEstimate(
        @PathVariable UUID tenantId,
        @PathVariable UUID incidentId
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(Map.of("estimatedCents", costService.estimateResponseCost(tenantId, incidentId)));
    }

    @PostMapping("/estimate/record")
    @Operation(summary = "Persist the auto-estimated responder-time cost as a cost entry, replacing any previous estimate")
    public ApiResponse<CostEntryResponse> recordEstimate(
        @PathVariable UUID tenantId,
        @PathVariable UUID incidentId,
        @RequestBody(required = false) Map<String, String> body
    ) {
        TenantContext.requireOwn(tenantId);
        String recordedBy = body != null ? body.get("recordedBy") : null;
        return ApiResponse.success(costService.recordAutoEstimate(tenantId, incidentId, recordedBy));
    }
}
