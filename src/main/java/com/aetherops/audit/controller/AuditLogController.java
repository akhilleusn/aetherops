package com.aetherops.audit.controller;

import com.aetherops.audit.model.AuditLogResponse;
import com.aetherops.audit.service.AuditLogService;
import com.aetherops.common.response.ApiResponse;
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
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Audit Logs", description = "Tenant-scoped audit trail for AI operations")
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping("/{id}/audit-logs")
    @Operation(summary = "List audit log entries for a tenant, sorted most recent first.")
    public ApiResponse<Page<AuditLogResponse>> getAuditLogs(
        @PathVariable UUID id,
        @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ApiResponse.success(auditLogService.getAuditLogs(id, pageable));
    }
}
