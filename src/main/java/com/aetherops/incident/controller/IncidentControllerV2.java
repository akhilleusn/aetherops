package com.aetherops.incident.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.incident.model.IncidentResponse;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v2/incidents")
@RequiredArgsConstructor
@Tag(name = "Incidents v2", description = "Incidents API v2 — stable versioned endpoint")
public class IncidentControllerV2 {

    private final IncidentService incidentService;

    @GetMapping("/{id}")
    @Operation(summary = "Get incident by ID (v2)")
    public ApiResponse<IncidentResponse> getById(@PathVariable UUID id) {
        return ApiResponse.success(incidentService.findById(id));
    }

    @GetMapping
    @Operation(summary = "List incidents for a tenant (v2)")
    public ApiResponse<Page<IncidentResponse>> list(
            @RequestParam UUID tenantId,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) String serviceId,
            @PageableDefault(size = 20, sort = "lastSeenAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        // Phase 2 remediation, finding 5 sweep (see STEP_REPORT.md) -- found missing during
        // the controller-wide sweep for this exact shape; no other endpoint in this file or
        // codebase had this specific gap.
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(
                incidentService.findAll(tenantId, status, serviceId, null, null, pageable));
    }
}