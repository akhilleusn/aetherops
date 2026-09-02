package com.aetherops.retention.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.retention.model.RetentionResult;
import com.aetherops.retention.service.RetentionService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Retention", description = "Tenant data retention management")
public class RetentionController {

    private final RetentionService retentionService;

    @PostMapping("/{id}/retention/run")
    @Operation(summary = "Run the retention policy for a tenant. "
        + "Defaults to dryRun=true — counts eligible rows without deleting. "
        + "Pass ?dryRun=false to perform actual deletion.")
    public ApiResponse<RetentionResult> run(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "true") boolean dryRun
    ) {
        TenantContext.requireOwn(id);
        return ApiResponse.success(retentionService.run(id, dryRun));
    }
}
