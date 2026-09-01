package com.aetherops.monitoring.service;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.oncall.IncidentPagingService;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.slack.IncidentSlackNotificationService;
import com.aetherops.suppression.AlertSuppressionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonitoringAlertIngestionServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private IncidentSlackNotificationService slackNotificationService;
    @Mock private IncidentTimelineService timelineService;
    @Mock private IncidentPagingService pagingService;
    @Mock private AlertSuppressionService alertSuppressionService;

    @InjectMocks
    private MonitoringAlertIngestionService ingestionService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();

    private MonitoringAlertIngestionService.AlertSignal signal(String serviceId, String alertName,
                                                                IncidentSeverity severity, boolean firing) {
        return new MonitoringAlertIngestionService.AlertSignal(
            tenantId, projectId, serviceId, alertName, "Alert: " + alertName, "details",
            severity, firing, "production", Instant.now());
    }

    @Test
    void ingest_firingAlert_noExistingIncident_createsOne() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, true));

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceId()).isEqualTo("db-svc");
        assertThat(captor.getValue().getSeverity()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(captor.getValue().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(captor.getValue().getEventCount()).isEqualTo(1);
        verify(slackNotificationService).notify(any());
    }

    @Test
    void ingest_firingAlert_lowSeverity_doesNotNotifySlack() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "minor_thing", IncidentSeverity.LOW, true));

        verifyNoInteractions(slackNotificationService);
    }

    @Test
    void ingest_resolvedAlert_noExistingIncident_doesNothing() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, false));

        verify(incidentRepository, never()).save(any());
    }

    @Test
    void ingest_repeatedFiringAlert_updatesExistingIncidentEventCount() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("db-svc").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();

        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, true));

        ArgumentCaptor<Incident> captor = ArgumentCaptor.forClass(Incident.class);
        verify(incidentRepository).save(captor.capture());
        assertThat(captor.getValue().getEventCount()).isEqualTo(2);
    }

    @Test
    void ingest_repeatedFiringAlert_escalatesSeverity() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.MEDIUM).serviceId("db-svc").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();

        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.CRITICAL, true));

        assertThat(existing.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        verify(slackNotificationService).notify(any());
    }

    @Test
    void ingest_repeatedFiringAlert_neverDowngradesSeverity() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.CRITICAL).serviceId("db-svc").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();

        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.LOW, true));

        assertThat(existing.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
    }

    @Test
    void ingest_resolvedAlert_withExistingOpenIncident_resolvesIt() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("db-svc").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();

        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, false));

        assertThat(existing.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(existing.getResolvedAt()).isNotNull();
    }

    // ── timeline + paging wiring ─────────────────────────────────────────

    @Test
    void ingest_newIncident_recordsCreatedTimelineEvent() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, true));

        verify(timelineService).record(eq(tenantId), any(), eq(com.aetherops.oncall.IncidentTimeline.TimelineEventType.CREATED),
            eq(IncidentSeverity.HIGH), any());
    }

    @Test
    void ingest_alertableNewIncident_triggersPaging() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.CRITICAL, true));

        verify(pagingService).maybePage(eq(tenantId), any(), eq("db-svc"), any(), eq(IncidentSeverity.CRITICAL));
    }

    @Test
    void ingest_resolvedAlert_recordsResolvedTimelineEvent() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.HIGH).serviceId("db-svc").eventCount(1)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, false));

        verify(timelineService).record(eq(tenantId), eq(existing.getId()),
            eq(com.aetherops.oncall.IncidentTimeline.TimelineEventType.RESOLVED), eq(IncidentSeverity.HIGH), any());
    }

    // ── alert suppression wiring ─────────────────────────────────────────

    @Test
    void ingest_suppressedAlert_skipsEntirelyWithoutTouchingIncidentRepository() {
        when(alertSuppressionService.shouldSuppress(tenantId, "db-svc", "high_cpu")).thenReturn(true);

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.CRITICAL, true));

        verifyNoInteractions(incidentRepository);
        verifyNoInteractions(slackNotificationService);
        verifyNoInteractions(pagingService);
    }

    @Test
    void ingest_notSuppressed_proceedsNormally() {
        when(alertSuppressionService.shouldSuppress(any(), any(), any())).thenReturn(false);
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ingestionService.ingest(signal("db-svc", "high_cpu", IncidentSeverity.HIGH, true));

        verify(incidentRepository).save(any());
    }
}
