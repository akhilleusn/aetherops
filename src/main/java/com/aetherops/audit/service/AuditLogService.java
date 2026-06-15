package com.aetherops.audit.service;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditLog;
import com.aetherops.audit.model.AuditLogResponse;
import com.aetherops.audit.model.AuditStatus;
import com.aetherops.audit.repository.AuditLogRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.repository.TenantRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final TenantRepository tenantRepository;

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
    public Page<AuditLogResponse> getAuditLogs(
            UUID tenantId,
            AuditAction action,
            AuditStatus status,
            Instant from,
            Instant to,
            Pageable pageable
    ) {
        tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + tenantId));

        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (status != null) predicates.add(cb.equal(root.get("status"), status));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditLogRepository.findAll(spec, pageable).map(this::toResponse);
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