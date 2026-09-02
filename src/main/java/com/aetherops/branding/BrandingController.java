package com.aetherops.branding;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/branding")
@RequiredArgsConstructor
@Tag(name = "Branding", description = "Custom branding for status pages and future white-labeling")
public class BrandingController {

    private final BrandingService brandingService;

    @PutMapping
    @Operation(summary = "Configure custom branding for a tenant (company name, logo, colors)")
    public ApiResponse<BrandingResponse> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody BrandingRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(brandingService.configure(tenantId, request));
    }

    @GetMapping
    @Operation(summary = "Get the tenant's custom branding, if configured")
    public ApiResponse<BrandingResponse> get(@PathVariable UUID tenantId) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(brandingService.get(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("No branding configured for tenant: " + tenantId)));
    }
}
