package com.aetherops.analytics;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.service.model.Service;
import com.aetherops.service.model.ServiceEnvironment;
import com.aetherops.service.repository.ServiceRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private ServiceRepository serviceRepository;

    @InjectMocks
    private AnalyticsService analyticsService;

    private final UUID tenantId = UUID.randomUUID();
    private final Instant from = Instant.now().minus(30, ChronoUnit.DAYS);
    private final Instant to = Instant.now();

    private Incident incident(String serviceId, Instant firstSeen, Instant resolvedAt, IncidentSeverity severity) {
        return Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).serviceId(serviceId)
            .status(resolvedAt != null ? IncidentStatus.RESOLVED : IncidentStatus.OPEN)
            .severity(severity).firstSeenAt(firstSeen).lastSeenAt(firstSeen).resolvedAt(resolvedAt)
            .build();
    }

    @Test
    void getDashboard_noIncidents_returnsZeroedResponse() {
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any())).thenReturn(List.of());
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getTotalIncidents()).isZero();
        assertThat(response.getResolvedCount()).isZero();
        assertThat(response.getMttrMinutes()).isNull();
        assertThat(response.getMtbfMinutes()).isNull();
        assertThat(response.getTrend()).isEmpty();
        assertThat(response.getTeamPerformance()).isEmpty();
    }

    @Test
    void getDashboard_computesMttrFromResolvedIncidentsOnly() {
        Instant start = Instant.now().minus(2, ChronoUnit.HOURS);
        Incident resolvedIn60 = incident("svc-a", start, start.plus(60, ChronoUnit.MINUTES), IncidentSeverity.HIGH);
        Incident resolvedIn30 = incident("svc-a", start, start.plus(30, ChronoUnit.MINUTES), IncidentSeverity.HIGH);
        Incident stillOpen = incident("svc-a", start, null, IncidentSeverity.HIGH);

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(resolvedIn60, resolvedIn30, stillOpen));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getTotalIncidents()).isEqualTo(3);
        assertThat(response.getResolvedCount()).isEqualTo(2);
        assertThat(response.getMttrMinutes()).isEqualTo(45.0); // avg(60, 30)
    }

    @Test
    void getDashboard_singleIncident_mtbfIsNull() {
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(incident("svc-a", Instant.now(), null, IncidentSeverity.LOW)));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getMtbfMinutes()).isNull();
    }

    @Test
    void getDashboard_computesMtbfBetweenConsecutiveIncidents() {
        Instant t0 = Instant.now().minus(3, ChronoUnit.HOURS);
        Incident i1 = incident("svc-a", t0, null, IncidentSeverity.LOW);
        Incident i2 = incident("svc-a", t0.plus(60, ChronoUnit.MINUTES), null, IncidentSeverity.LOW);
        Incident i3 = incident("svc-a", t0.plus(180, ChronoUnit.MINUTES), null, IncidentSeverity.LOW);

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(i3, i1, i2)); // unsorted on purpose
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        // gaps: 60min, 120min -> avg 90min
        assertThat(response.getMtbfMinutes()).isEqualTo(90.0);
    }

    @Test
    void getDashboard_teamPerformance_groupsByOwnerTeam() {
        Instant now = Instant.now();
        Incident dbIncident1 = incident("db-svc", now, null, IncidentSeverity.HIGH);
        Incident dbIncident2 = incident("db-svc", now, null, IncidentSeverity.HIGH);
        Incident apiIncident = incident("api-svc", now, null, IncidentSeverity.LOW);

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(dbIncident1, dbIncident2, apiIncident));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of(
            Service.builder().name("db-svc").environment(ServiceEnvironment.PRODUCTION).ownerTeam("platform").build(),
            Service.builder().name("api-svc").environment(ServiceEnvironment.PRODUCTION).ownerTeam("api-team").build()));

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getTeamPerformance()).hasSize(2);
        assertThat(response.getTeamPerformance().get(0).getTeam()).isEqualTo("platform");
        assertThat(response.getTeamPerformance().get(0).getIncidentCount()).isEqualTo(2);
    }

    @Test
    void getDashboard_serviceWithNoOwnerTeam_groupedAsUnassigned() {
        Instant now = Instant.now();
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(incident("orphan-svc", now, null, IncidentSeverity.LOW)));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getTeamPerformance()).hasSize(1);
        assertThat(response.getTeamPerformance().get(0).getTeam()).isEqualTo("Unassigned");
    }

    @Test
    void getDashboard_trend_groupsIncidentsByDate() {
        Instant now = Instant.now();
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any()))
            .thenReturn(List.of(
                incident("svc-a", now, null, IncidentSeverity.LOW),
                incident("svc-a", now, null, IncidentSeverity.LOW)));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getTrend()).hasSize(1);
        assertThat(response.getTrend().get(0).getCount()).isEqualTo(2);
    }

    @Test
    void getDashboard_simultaneousIncidents_mtbfGapCanBeZero() {
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS);
        Incident i1 = incident("svc-a", t0, null, IncidentSeverity.LOW);
        Incident i2 = incident("svc-b", t0, null, IncidentSeverity.LOW);

        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any())).thenReturn(List.of(i1, i2));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getMtbfMinutes()).isEqualTo(0.0);
    }

    @Test
    void getDashboard_periodBoundariesEchoedBackOnResponse() {
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any())).thenReturn(List.of());
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getPeriodStart()).isEqualTo(from);
        assertThat(response.getPeriodEnd()).isEqualTo(to);
        assertThat(response.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void getDashboard_teamPerformance_sortedByIncidentCountDescending() {
        Instant now = Instant.now();
        when(incidentRepository.findByTenantAndPeriod(eq(tenantId), any(), any())).thenReturn(List.of(
            incident("small-svc", now, null, IncidentSeverity.LOW),
            incident("big-svc", now, null, IncidentSeverity.HIGH),
            incident("big-svc", now, null, IncidentSeverity.HIGH),
            incident("big-svc", now, null, IncidentSeverity.HIGH)));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of(
            Service.builder().name("small-svc").environment(ServiceEnvironment.PRODUCTION).ownerTeam("team-small").build(),
            Service.builder().name("big-svc").environment(ServiceEnvironment.PRODUCTION).ownerTeam("team-big").build()));

        AnalyticsDashboardResponse response = analyticsService.getDashboard(tenantId, from, to);

        assertThat(response.getTeamPerformance()).hasSize(2);
        assertThat(response.getTeamPerformance().get(0).getTeam()).isEqualTo("team-big");
        assertThat(response.getTeamPerformance().get(0).getIncidentCount()).isEqualTo(3);
        assertThat(response.getTeamPerformance().get(1).getTeam()).isEqualTo("team-small");
    }
}
