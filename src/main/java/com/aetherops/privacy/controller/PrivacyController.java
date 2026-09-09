package com.aetherops.privacy.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.privacy.model.TenantDataExportResponse;
import com.aetherops.privacy.model.TenantErasureResult;
import com.aetherops.privacy.service.TenantDataExportService;
import com.aetherops.privacy.service.TenantErasureService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Most controllers in this codebase trust a caller-supplied tenantId, relying on
 * PostgreSQL RLS as the isolation backstop — but RLS only covers 3 of the 38
 * tenant-scoped tables today. These two endpoints are the most destructive in the
 * system (full tenant data export / erasure), so they additionally require the path
 * tenant to match the caller's own JWT-derived tenant, mirroring the identity check
 * CalendarConnectionController uses for user-scoped resources.
 */
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Privacy", description = "GDPR data subject rights: export (Art. 20) and erasure (Art. 17)")
public class PrivacyController {

    private final TenantDataExportService exportService;
    private final TenantErasureService erasureService;

    @GetMapping("/{id}/privacy/export")
    @Operation(summary = "Export every row held for a tenant, across all tenant-scoped tables, as JSON.")
    public ApiResponse<TenantDataExportResponse> export(@PathVariable UUID id) {
        TenantContext.requireOwn(id);
        return ApiResponse.success(exportService.export(id));
    }

    @PostMapping("/{id}/privacy/erase")
    @PreAuthorize("hasRole('OWNER')")
    @Operation(summary = "Erase every row held for a tenant, then the tenant itself. "
        + "Defaults to dryRun=true — counts rows without deleting anything. "
        + "Pass ?dryRun=false to actually erase. Irreversible.")
    public ApiResponse<TenantErasureResult> erase(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        TenantContext.requireOwn(id);
        return ApiResponse.success(erasureService.erase(id, dryRun));
    }
}
