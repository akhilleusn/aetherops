package com.aetherops.audit.service;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditLog;
import com.aetherops.audit.model.AuditLogResponse;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.repository.AuditLogRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final TenantRepository tenantRepository;

    /**
     * Records an audit event.
     *
     * Uses default transaction propagation (REQUIRED). Behaviour by call site:
     * - Called from within an active @Transactional method (SUCCESS events): joins that
     *   transaction. If the outer transaction rolls back, the audit entry is rolled back too,
     *   which is correct — a failed generation should not appear as SUCCESS.
     * - Called from GlobalExceptionHandler (BLOCKED events): no active transaction, so a
     *   fresh transaction is started and committed independently.
     *
     * The message parameter must always be a safe, pre-formatted string.
     * Never pass raw log messages, evidence summaries, or any user-provided content.
     */
    @Transactional
    public void record(UUID tenantId, AuditAction action, AuditStatus status,
                       String resourceType, UUID resourceId, String message) {
        AuditLog entry = AuditLog.builder()
            .tenantId(tenantId)
            .action(action)
            .status(status)
            .resourceType(resourceType)
            .resourceId(resourceId)
            .message(message)
            .build();

        auditLogRepository.save(entry);
        log.debug("Audit: tenant={} action={} status={} resourceType={} resourceId={}",
            tenantId, action, status, resourceType, resourceId);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogs(UUID tenantId, Pageable pageable) {
        tenantRepository.findById(tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        return auditLogRepository.findByTenantId(tenantId, pageable)
            .map(this::toResponse);
    }

    private AuditLogResponse toResponse(AuditLog log) {
        return AuditLogResponse.builder()
            .id(log.getId())
            .tenantId(log.getTenantId())
            .action(log.getAction())
            .status(log.getStatus())
            .resourceType(log.getResourceType())
            .resourceId(log.getResourceId())
            .message(log.getMessage())
            .createdAt(log.getCreatedAt())
            .build();
    }
}
