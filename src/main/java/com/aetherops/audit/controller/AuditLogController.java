package com.aetherops.audit.controller;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditLogResponse;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.service.AuditLogExportService;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Tenant-scoped audit trail for AI operations")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final AuditLogExportService auditLogExportService;

    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "List audit log entries for a tenant with optional filters.")
    public ApiResponse<Page<AuditLogResponse>> getAuditLogs(
            @PathVariable UUID id,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) AuditStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        TenantContext.requireOwn(id);
        return ApiResponse.success(auditLogService.getAuditLogs(id, action, status, from, to, pageable));
    }

    @GetMapping("/{id}/audit-logs/export")
    @Operation(summary = "Export audit logs as CSV or JSON.")
    public ResponseEntity<String> exportAuditLogs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) AuditAction action
    ) {
        TenantContext.requireOwn(id);
        if ("json".equalsIgnoreCase(format)) {
            String json = auditLogExportService.exportAsJson(id, from, to, action);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        }

        String csv = auditLogExportService.exportAsCsv(id, from, to, action);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-logs.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
