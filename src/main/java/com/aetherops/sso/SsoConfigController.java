package com.aetherops.sso;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/sso")
@RequiredArgsConstructor
@Tag(name = "SSO", description = "Tenant SAML SSO configuration (enterprise)")
public class SsoConfigController {

    private final SsoConfigService ssoConfigService;

    @PutMapping
    @Operation(summary = "Configure SAML SSO for a tenant with their IdP's metadata")
    public ApiResponse<TenantSsoConfigResponse> configure(
        @PathVariable UUID tenantId,
        @Valid @RequestBody ConfigureSsoRequest request
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(ssoConfigService.configure(tenantId, request));
    }

    @GetMapping
    @Operation(summary = "Get the tenant's SAML SSO configuration, including the SP metadata (ACS URL, entity ID) to give their IdP admin")
    public ApiResponse<TenantSsoConfigResponse> get(@PathVariable UUID tenantId) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(ssoConfigService.get(tenantId));
    }
}
