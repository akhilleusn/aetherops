package com.aetherops.report.service;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.report.model.IncidentReport;
import com.aetherops.report.repository.IncidentReportRepository;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.repository.TenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentReportService {

    private final IncidentReportRepository reportRepository;
    private final IncidentRepository incidentRepository;
    private final TenantRepository tenantRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public IncidentReport generateWeeklyReport(UUID tenantId) {
        Instant periodEnd   = Instant.now();
        Instant periodStart = periodEnd.minus(7, ChronoUnit.DAYS);

        List<Incident> incidents = incidentRepository
                .findByTenantAndPeriod(tenantId, periodStart, periodEnd);

        long total    = incidents.size();
        long resolved = incidents.stream()
                .filter(i -> i.getStatus() == IncidentStatus.RESOLVED
                          || i.getStatus() == IncidentStatus.CLOSED)
                .count();

        double mttrMinutes = incidents.stream()
                .filter(i -> i.getResolvedAt() != null && i.getFirstSeenAt() != null)
                .mapToLong(i -> ChronoUnit.MINUTES.between(i.getFirstSeenAt(), i.getResolvedAt()))
                .average()
                .orElse(0.0);

        List<Map<String, Object>> topServices = incidents.stream()
                .collect(Collectors.groupingBy(Incident::getServiceId, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> Map.<String, Object>of("service", e.getKey(), "count", e.getValue()))
                .collect(Collectors.toList());

        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("tenantId", tenantId);
            report.put("periodStart", periodStart);
            report.put("periodEnd", periodEnd);
            report.put("totalIncidents", total);
            report.put("resolvedCount", resolved);
            report.put("mttrMinutes", Math.round(mttrMinutes * 10.0) / 10.0);
            report.put("topServices", topServices);

            String json = objectMapper.writeValueAsString(report);

            IncidentReport saved = reportRepository.save(IncidentReport.builder()
                    .tenantId(tenantId)
                    .periodStart(periodStart)
                    .periodEnd(periodEnd)
                    .reportJson(json)
                    .build());

            log.info("Generated weekly report id={} tenant={} total={} resolved={} mttrMin={}",
                    saved.getId(), tenantId, total, resolved, mttrMinutes);
            return saved;

        } catch (Exception e) {
            log.error("Failed to generate weekly report for tenant={}", tenantId, e);
            throw new IllegalStateException("Report generation failed for tenant " + tenantId, e);
        }
    }

    @Scheduled(cron = "0 0 8 * * MON")
    @Transactional
    public void generateAllTenantReports() {
        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Generating weekly reports for {} tenants", tenants.size());
        for (Tenant tenant : tenants) {
            try {
                generateWeeklyReport(tenant.getId());
            } catch (Exception e) {
                log.error("Weekly report failed for tenant={}: {}", tenant.getId(), e.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<IncidentReport> getReports(UUID tenantId, Pageable pageable) {
        return reportRepository.findByTenantIdOrderByGeneratedAtDesc(tenantId, pageable);
    }
}
