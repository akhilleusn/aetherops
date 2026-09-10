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

    // Phase 2 remediation, finding 10 (see STEP_REPORT.md): csvEscape must neutralize a
    // leading =, +, - or @ (CSV/formula injection), not just guard comma/quote/newline.

    @Test
    void exportAsCsv_neutralizesLeadingEqualsInMessage() {
        UUID tenantId = UUID.randomUUID();
        AuditLog log = AuditLog.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .action(AuditAction.ANALYSIS_GENERATED)
                .message("=cmd|' /C calc'!A1")
                .createdAt(Instant.now())
                .build();
        when(auditLogRepository.findAll(any(Specification.class))).thenReturn(List.of(log));

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        String csv = service.exportAsCsv(tenantId, null, null, null);

        assertThat(csv).contains("'=cmd|' /C calc'!A1");
        assertThat(csv).doesNotContain(",=cmd");
    }

    // resourceId is a UUID on the domain model, so a live end-to-end "=-leading resourceId"
    // cannot actually be constructed (part of why finding 10 calls this "not exploitable
    // today") — but the CSV column for resourceId is written through this exact same private
    // csvEscape function as message, so this proves that shared function neutralizes a
    // leading special character regardless of which column calls it.
    @Test
    void csvEscape_neutralizesLeadingSpecialCharacters_sharedByResourceIdColumn() {
        for (String trigger : List.of("=1+1", "+1+1", "-1+1", "@SUM(1)")) {
            String escaped = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "csvEscape", trigger);
            assertThat(escaped).as("escaping of " + trigger).startsWith("'" + trigger.charAt(0));
        }
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
