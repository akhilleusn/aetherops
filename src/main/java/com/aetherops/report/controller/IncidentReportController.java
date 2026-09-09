package com.aetherops.report.controller;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.report.model.IncidentReport;
import com.aetherops.report.service.IncidentReportService;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reports")
@RequiredArgsConstructor
@Tag(name = "Incident Reports", description = "Scheduled and on-demand incident reports")
public class IncidentReportController {

    private final IncidentReportService reportService;

    @GetMapping
    @Operation(summary = "List all incident reports for a tenant")
    public ApiResponse<Page<IncidentReport>> listReports(
            @PathVariable UUID tenantId,
            @PageableDefault(size = 10, sort = "generatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(reportService.getReports(tenantId, pageable));
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Trigger an on-demand weekly report for the tenant")
    public ApiResponse<IncidentReport> generate(@PathVariable UUID tenantId) {
        TenantContext.requireOwn(tenantId);
        return ApiResponse.success(reportService.generateWeeklyReport(tenantId));
    }
}
