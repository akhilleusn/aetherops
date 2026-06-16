package com.aetherops.report;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.report.model.IncidentReport;
import com.aetherops.report.repository.IncidentReportRepository;
import com.aetherops.report.service.IncidentReportService;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentReportServiceTest {

    @Mock private IncidentReportRepository reportRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private TenantRepository tenantRepository;
    @InjectMocks private IncidentReportService service;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void generateWeeklyReport_savesReport_withCorrectCounts() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();

        Incident open = Incident.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(IncidentStatus.OPEN).serviceId("svc-a")
                .firstSeenAt(now.minus(2, ChronoUnit.DAYS)).lastSeenAt(now)
                .build();
        Incident resolved = Incident.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(IncidentStatus.RESOLVED).serviceId("svc-b")
                .firstSeenAt(now.minus(3, ChronoUnit.DAYS))
                .lastSeenAt(now.minus(1, ChronoUnit.DAYS))
                .resolvedAt(now.minus(1, ChronoUnit.DAYS))
                .build();

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(open, resolved));
        when(reportRepository.save(any(IncidentReport.class))).thenAnswer(inv -> {
            IncidentReport r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        IncidentReport report = service.generateWeeklyReport(tenantId);

        assertThat(report).isNotNull();
        assertThat(report.getTenantId()).isEqualTo(tenantId);
        assertThat(report.getReportJson()).contains("\"totalIncidents\":2");
        assertThat(report.getReportJson()).contains("\"resolvedCount\":1");
    }

    @Test
    void generateWeeklyReport_calculatesMttr() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant firstSeen = now.minus(120, ChronoUnit.MINUTES);
        Instant resolvedAt = now.minus(60, ChronoUnit.MINUTES);

        Incident resolved = Incident.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(IncidentStatus.RESOLVED).serviceId("svc-a")
                .firstSeenAt(firstSeen).lastSeenAt(resolvedAt)
                .resolvedAt(resolvedAt)
                .build();

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
                .thenReturn(List.of(resolved));
        when(reportRepository.save(any(IncidentReport.class))).thenAnswer(inv -> inv.getArgument(0));

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        IncidentReport report = service.generateWeeklyReport(tenantId);

        assertThat(report.getReportJson()).contains("mttrMinutes");
        assertThat(report.getReportJson()).contains("60.0");
    }

    @Test
    void generateWeeklyReport_emptyIncidents_returnsZeroCounts() {
        UUID tenantId = UUID.randomUUID();
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
                .thenReturn(List.of());
        when(reportRepository.save(any(IncidentReport.class))).thenAnswer(inv -> inv.getArgument(0));

        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        IncidentReport report = service.generateWeeklyReport(tenantId);

        assertThat(report.getReportJson()).contains("\"totalIncidents\":0");
        assertThat(report.getReportJson()).contains("\"resolvedCount\":0");
    }
}
