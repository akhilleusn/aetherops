package com.aetherops.escalation;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/escalation-policies")
@RequiredArgsConstructor
@Tag(name = "Auto-Escalation", description = "Escalate unresolved incidents that no one has acknowledged")
public class EscalationPolicyController {

    private final EscalationPolicyService policyService;

    @PutMapping
    @Operation(summary = "Configure the escalation policy for a service, or the tenant-wide default when serviceId is omitted")
    public ApiResponse<EscalationPolicyResponse> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody EscalationPolicyRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(policyService.configure(tenantId, request));
    }

    @GetMapping
    @Operation(summary = "List all escalation policies configured for a tenant")
    public ApiResponse<List<EscalationPolicyResponse>> list(@PathVariable UUID tenantId) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(policyService.list(tenantId));
    }

    @DeleteMapping("/{policyId}")
    @Operation(summary = "Remove an escalation policy (the hardcoded default — CRITICAL after 5 minutes — applies once removed)")
    public ApiResponse<Void> delete(@PathVariable UUID tenantId, @PathVariable UUID policyId) {
        TenantContext.requireOwn(tenantId);
        policyService.delete(tenantId, policyId);
        return ApiResponse.success(null);
    }
}
