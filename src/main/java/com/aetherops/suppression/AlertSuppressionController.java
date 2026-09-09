package com.aetherops.suppression;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/alert-suppression")
@RequiredArgsConstructor
@Tag(name = "Alert Suppression", description = "Mute noisy alerts, manually or via automatic flood detection")
public class AlertSuppressionController {

    private final AlertSuppressionService suppressionService;

    @PostMapping
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Mute alerts matching a service and/or alert name, optionally until a given time")
    public ApiResponse<AlertSuppressionRuleResponse> mute(
        @PathVariable UUID tenantId,
        @RequestBody MuteAlertRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(suppressionService.mute(tenantId, request));
    }

    @GetMapping
    @Operation(summary = "List currently-active suppression rules for a tenant")
    public ApiResponse<List<AlertSuppressionRuleResponse>> list(@PathVariable UUID tenantId) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(suppressionService.listActive(tenantId));
    }

    @DeleteMapping("/{ruleId}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Remove a suppression rule (unmute)")
    public ApiResponse<Void> unmute(@PathVariable UUID tenantId, @PathVariable UUID ruleId) {
        TenantContext.requireOwn(tenantId);
        suppressionService.unmute(tenantId, ruleId);
        return ApiResponse.success(null);
    }
}
