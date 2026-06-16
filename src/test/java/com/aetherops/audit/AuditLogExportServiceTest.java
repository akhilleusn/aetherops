package com.aetherops.audit;

import com.aetherops.audit.model.AuditAction;
import com.aetherops.audit.model.AuditLog;
import com.aetherops.audit.repository.AuditLogRepository;
import com.aetherops.audit.service.AuditLogExportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogExportServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @InjectMocks private AuditLogExportService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exportAsCsv_includesHeaderRow() {
        UUID tenantId = UUID.randomUUID();
        when(auditLogRepository.findAll(any(Specification.class))).thenReturn(List.of());

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        String csv = service.exportAsCsv(tenantId, null, null, null);

        assertThat(csv).startsWith("id,tenantId,action,status,resourceType,resourceId,message,createdAt");
    }

    @Test
    void exportAsCsv_includesLogEntries() {
        UUID tenantId = UUID.randomUUID();
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .action(AuditAction.ANALYSIS_GENERATED)
                .message("test message")
                .createdAt(Instant.now())
                .build();
        when(auditLogRepository.findAll(any(Specification.class))).thenReturn(List.of(log));

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        String csv = service.exportAsCsv(tenantId, null, null, null);

        assertThat(csv).contains(log.getId().toString());
        assertThat(csv).contains("ANALYSIS_GENERATED");
        assertThat(csv).contains("test message");
    }

    @Test
    void exportAsCsv_escapesCommasInMessage() {
        UUID tenantId = UUID.randomUUID();
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .action(AuditAction.ANALYSIS_GENERATED)
                .message("message, with, commas")
                .createdAt(Instant.now())
                .build();
        when(auditLogRepository.findAll(any(Specification.class))).thenReturn(List.of(log));

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        String csv = service.exportAsCsv(tenantId, null, null, null);

        assertThat(csv).contains("\"message, with, commas\"");
    }

    @Test
    void exportAsJson_returnsValidJsonArray() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(auditLogRepository.findAll(any(Specification.class))).thenReturn(List.of());

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        String json = service.exportAsJson(tenantId, null, null, null);

        assertThat(json).isEqualTo("[]");
    }
}
