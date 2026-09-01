package com.aetherops.analytics;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.service.model.Service;
import com.aetherops.service.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class AnalyticsService {

    private static final String UNASSIGNED_TEAM = "Unassigned";

    private final IncidentRepository incidentRepository;
    private final ServiceRepository serviceRepository;

    @Transactional(readOnly = true)
    public AnalyticsDashboardResponse getDashboard(UUID tenantId, Instant from, Instant to) {
        List<Incident> incidents = incidentRepository.findByTenantAndPeriod(tenantId, from, to);

        List<Incident> resolved = incidents.stream()
            .filter(i -> i.getResolvedAt() != null)
            .toList();

        return AnalyticsDashboardResponse.builder()
            .tenantId(tenantId)
            .periodStart(from)
            .periodEnd(to)
            .totalIncidents(incidents.size())
            .resolvedCount(resolved.size())
            .mttrMinutes(mttr(resolved))
            .mtbfMinutes(mtbf(incidents))
            .trend(dailyTrend(incidents))
            .teamPerformance(teamPerformance(tenantId, incidents))
            .build();
    }

    private Double mttr(List<Incident> resolved) {
        if (resolved.isEmpty()) return null;
        double avgMinutes = resolved.stream()
            .mapToLong(i -> Duration.between(i.getFirstSeenAt(), i.getResolvedAt()).toMinutes())
            .average()
            .orElse(0.0);
        return round(avgMinutes);
    }

    private Double mtbf(List<Incident> incidents) {
        if (incidents.size() < 2) return null;
        List<Instant> starts = incidents.stream()
            .map(Incident::getFirstSeenAt)
            .sorted()
            .toList();

        double totalMinutes = 0;
        for (int i = 1; i < starts.size(); i++) {
            totalMinutes += Duration.between(starts.get(i - 1), starts.get(i)).toMinutes();
        }
        return round(totalMinutes / (starts.size() - 1));
    }

    private List<AnalyticsDashboardResponse.DailyCount> dailyTrend(List<Incident> incidents) {
        Map<LocalDate, Long> byDate = incidents.stream()
            .collect(Collectors.groupingBy(
                i -> i.getFirstSeenAt().atZone(ZoneOffset.UTC).toLocalDate(),
                Collectors.counting()));

        return byDate.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> AnalyticsDashboardResponse.DailyCount.builder()
                .date(e.getKey().toString())
                .count(e.getValue().intValue())
                .build())
            .toList();
    }

    private List<AnalyticsDashboardResponse.TeamPerformance> teamPerformance(UUID tenantId, List<Incident> incidents) {
        Map<String, String> serviceToTeam = serviceRepository.findByTenantId(tenantId).stream()
            .filter(s -> s.getOwnerTeam() != null && !s.getOwnerTeam().isBlank())
            .collect(Collectors.toMap(Service::getName, Service::getOwnerTeam, (a, b) -> a));

        Map<String, List<Incident>> byTeam = incidents.stream()
            .collect(Collectors.groupingBy(i -> serviceToTeam.getOrDefault(i.getServiceId(), UNASSIGNED_TEAM)));

        return byTeam.entrySet().stream()
            .map(e -> {
                List<Incident> teamIncidents = e.getValue();
                List<Incident> teamResolved = teamIncidents.stream()
                    .filter(i -> i.getResolvedAt() != null)
                    .toList();
                return AnalyticsDashboardResponse.TeamPerformance.builder()
                    .team(e.getKey())
                    .incidentCount(teamIncidents.size())
                    .mttrMinutes(mttr(teamResolved))
                    .build();
            })
            .sorted(Comparator.comparing(AnalyticsDashboardResponse.TeamPerformance::getIncidentCount).reversed())
            .toList();
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
