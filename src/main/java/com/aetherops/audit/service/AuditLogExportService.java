package com.aetherops.audit.service;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditLog;
import com.aetherops.audit.repository.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogExportService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String exportAsCsv(UUID tenantId, Instant from, Instant to, AuditAction action) {
        List<AuditLog> logs = fetchLogs(tenantId, from, to, action);
        log.info("Exporting {} audit log entries as CSV for tenant={}", logs.size(), tenantId);

        StringBuilder csv = new StringBuilder();
        csv.append("id,tenantId,action,status,resourceType,resourceId,message,createdAt\n");
        for (AuditLog entry : logs) {
            csv.append(entry.getId()).append(',')
               .append(entry.getTenantId()).append(',')
               .append(entry.getAction()).append(',')
               .append(entry.getStatus()).append(',')
               .append(nvl(entry.getResourceType())).append(',')
               .append(nvl(entry.getResourceId())).append(',')
               .append(csvEscape(entry.getMessage())).append(',')
               .append(entry.getCreatedAt()).append('\n');
        }
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public String exportAsJson(UUID tenantId, Instant from, Instant to, AuditAction action) {
        List<AuditLog> logs = fetchLogs(tenantId, from, to, action);
        log.info("Exporting {} audit log entries as JSON for tenant={}", logs.size(), tenantId);
        try {
            return objectMapper.writeValueAsString(logs);
        } catch (Exception e) {
            log.error("Failed to serialise audit logs to JSON for tenant={}", tenantId, e);
            throw new IllegalStateException("JSON serialisation failed", e);
        }
    }

    private List<AuditLog> fetchLogs(UUID tenantId, Instant from, Instant to, AuditAction action) {
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (action != null) predicates.add(cb.equal(root.get("action"), action));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null)   predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return auditLogRepository.findAll(spec);
    }

    private String nvl(Object value) {
        return value == null ? "" : value.toString();
    }

    private String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
