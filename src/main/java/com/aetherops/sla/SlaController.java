package com.aetherops.sla;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sla")
@RequiredArgsConstructor
@Tag(name = "SLA", description = "Response/resolution SLA compliance")
public class SlaController {

    private final SlaTrackingService slaTrackingService;

    @GetMapping("/summary")
    @Operation(summary = "Get SLA compliance summary for a calendar month. Defaults to the current month.")
    public ApiResponse<SlaSummaryResponse> getSummary(
        @PathVariable UUID tenantId,
        @RequestParam(required = false) String period
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(slaTrackingService.getSummary(tenantId, period));
    }
}
