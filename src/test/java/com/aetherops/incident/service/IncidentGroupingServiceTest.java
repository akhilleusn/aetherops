package com.aetherops.incident.service;

import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.model.LogLevel;
import com.aetherops.oncall.IncidentPagingService;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.slack.SlackNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentGroupingServiceTest {

    @Mock private IncidentRepository incidentRepository;
    @Mock private EvidenceItemRepository evidenceItemRepository;
    @Mock private SlackNotifier slackNotifier;
    @Mock private IncidentCorrelationService correlationService;
    @Mock private IncidentTimelineService timelineService;
    @Mock private IncidentPagingService pagingService;

    @InjectMocks
    private IncidentGroupingService groupingService;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(incidentRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private LogEvent logEvent(LogLevel level) {
        return LogEvent.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).projectId(UUID.randomUUID())
            .serviceId("db-svc").environment("production")
            .level(level).message("connection refused")
            .occurredAt(Instant.now())
            .build();
    }

    @Test
    void findOrCreate_infoLevel_neverGroupsOrTouchesTimeline() {
        groupingService.findOrCreate(logEvent(LogLevel.INFO));

        verifyNoInteractions(incidentRepository, timelineService, pagingService);
    }

    @Test
    void findOrCreate_errorLevel_noExistingIncident_noCorrelation_createsAndRecordsTimeline() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(correlationService.correlate(eq(tenantId), eq("db-svc"), any(), any())).thenReturn(Optional.empty());

        groupingService.findOrCreate(logEvent(LogLevel.ERROR));

        verify(timelineService).record(eq(tenantId), any(), eq(IncidentTimeline.TimelineEventType.CREATED),
            eq(IncidentSeverity.MEDIUM), any());
    }

    @Test
    void findOrCreate_fatalLevel_newIncident_isImmediatelyAlertableAndPages() {
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(correlationService.correlate(eq(tenantId), eq("db-svc"), any(), any())).thenReturn(Optional.empty());

        groupingService.findOrCreate(logEvent(LogLevel.FATAL));

        verify(pagingService).maybePage(eq(tenantId), any(), eq("db-svc"), any(), eq(IncidentSeverity.CRITICAL));
        verify(slackNotifier).notify(any());
    }

    @Test
    void findOrCreate_errorLevel_newIncident_notAlertable_doesNotPage() {
        // Single ERROR (not FATAL) maps to MEDIUM initial severity, which isn't alertable.
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.empty());
        when(correlationService.correlate(eq(tenantId), eq("db-svc"), any(), any())).thenReturn(Optional.empty());

        groupingService.findOrCreate(logEvent(LogLevel.ERROR));

        verifyNoInteractions(pagingService);
    }

    @Test
    void findOrCreate_existingIncidentEscalates_recordsSeverityChangedAndPages() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.MEDIUM).serviceId("db-svc").eventCount(2)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));

        groupingService.findOrCreate(logEvent(LogLevel.FATAL));

        verify(timelineService).record(eq(tenantId), eq(existing.getId()),
            eq(IncidentTimeline.TimelineEventType.SEVERITY_CHANGED), eq(IncidentSeverity.CRITICAL), any());
        verify(pagingService).maybePage(eq(tenantId), eq(existing.getId()), eq("db-svc"), any(), eq(IncidentSeverity.CRITICAL));
    }

    @Test
    void findOrCreate_existingIncidentSameSeverity_doesNotRecordSeverityChanged() {
        Incident existing = Incident.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).status(IncidentStatus.OPEN)
            .severity(IncidentSeverity.CRITICAL).serviceId("db-svc").eventCount(5)
            .firstSeenAt(Instant.now()).lastSeenAt(Instant.now()).build();
        when(incidentRepository.findByTenantIdAndFingerprintAndStatus(eq(tenantId), any(), eq(IncidentStatus.OPEN)))
            .thenReturn(Optional.of(existing));

        groupingService.findOrCreate(logEvent(LogLevel.ERROR));

        verify(timelineService, never()).record(any(), any(), eq(IncidentTimeline.TimelineEventType.SEVERITY_CHANGED), any(IncidentSeverity.class), any());
    }
}
