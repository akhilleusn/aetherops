package com.aetherops.privacy.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.privacy.model.TenantDataExportResponse;
import com.aetherops.privacy.model.TenantErasureResult;
import com.aetherops.privacy.service.TenantDataExportService;
import com.aetherops.privacy.service.TenantErasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

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
        return ApiResponse.success(exportService.export(id));
    }

    @PostMapping("/{id}/privacy/erase")
    @Operation(summary = "Erase every row held for a tenant, then the tenant itself. "
        + "Defaults to dryRun=true — counts rows without deleting anything. "
        + "Pass ?dryRun=false to actually erase. Irreversible.")
    public ApiResponse<TenantErasureResult> erase(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        return ApiResponse.success(erasureService.erase(id, dryRun));
    }
}
