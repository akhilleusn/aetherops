package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentTimelineServiceTest {

    @Mock private IncidentTimelineRepository timelineRepository;

    @InjectMocks
    private IncidentTimelineService timelineService;

    @Test
    void record_withSeverityLevel_savesEntryVerbatim() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();

        timelineService.record(tenantId, incidentId, IncidentTimeline.TimelineEventType.ENGINEER_PAGED,
            SeverityLevel.P1, "paged on-call");

        ArgumentCaptor<IncidentTimeline> captor = ArgumentCaptor.forClass(IncidentTimeline.class);
        verify(timelineRepository).save(captor.capture());
        IncidentTimeline saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getIncidentId()).isEqualTo(incidentId);
        assertThat(saved.getEventType()).isEqualTo(IncidentTimeline.TimelineEventType.ENGINEER_PAGED);
        assertThat(saved.getSeverity()).isEqualTo(SeverityLevel.P1);
        assertThat(saved.getDetail()).isEqualTo("paged on-call");
    }

    @Test
    void record_withIncidentSeverity_mapsToSeverityLevel() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();

        timelineService.record(tenantId, incidentId, IncidentTimeline.TimelineEventType.CREATED,
            IncidentSeverity.CRITICAL, "created");

        ArgumentCaptor<IncidentTimeline> captor = ArgumentCaptor.forClass(IncidentTimeline.class);
        verify(timelineRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(SeverityLevel.P1);
    }

    @Test
    void findForIncident_mapsEntriesInRepositoryOrder() {
        UUID tenantId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-09-24T10:00:00Z");
        when(timelineRepository.findByTenantIdAndIncidentIdOrderByCreatedAtAsc(tenantId, incidentId)).thenReturn(List.of(
            entry(incidentId, IncidentTimeline.TimelineEventType.CREATED, SeverityLevel.P3, "Incident created", t0),
            entry(incidentId, IncidentTimeline.TimelineEventType.RESOLVED, SeverityLevel.P2, "Incident resolved", t0.plusSeconds(600))));

        List<IncidentTimelineResponse> result = timelineService.findForIncident(tenantId, incidentId);

        assertThat(result).extracting(IncidentTimelineResponse::getEventType)
            .containsExactly(IncidentTimeline.TimelineEventType.CREATED, IncidentTimeline.TimelineEventType.RESOLVED);
        assertThat(result.get(0).getIncidentId()).isEqualTo(incidentId);
        assertThat(result.get(0).getSeverity()).isEqualTo(SeverityLevel.P3);
        assertThat(result.get(0).getDetail()).isEqualTo("Incident created");
        assertThat(result.get(1).getCreatedAt()).isEqualTo(t0.plusSeconds(600));
    }

    @Test
    void findForIncident_noEntries_returnsEmptyList() {
        assertThat(timelineService.findForIncident(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    private static IncidentTimeline entry(UUID incidentId, IncidentTimeline.TimelineEventType type,
                                          SeverityLevel severity, String detail, Instant at) {
        return IncidentTimeline.builder()
            .id(UUID.randomUUID())
            .incidentId(incidentId)
            .eventType(type)
            .severity(severity)
            .detail(detail)
            .createdAt(at)
            .build();
    }

    @Test
    void record_lowIncidentSeverity_mapsToP4() {
        timelineService.record(UUID.randomUUID(), UUID.randomUUID(), IncidentTimeline.TimelineEventType.RESOLVED,
            IncidentSeverity.LOW, "resolved");

        ArgumentCaptor<IncidentTimeline> captor = ArgumentCaptor.forClass(IncidentTimeline.class);
        verify(timelineRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(SeverityLevel.P4);
    }
}
