package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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
    void record_lowIncidentSeverity_mapsToP4() {
        timelineService.record(UUID.randomUUID(), UUID.randomUUID(), IncidentTimeline.TimelineEventType.RESOLVED,
            IncidentSeverity.LOW, "resolved");

        ArgumentCaptor<IncidentTimeline> captor = ArgumentCaptor.forClass(IncidentTimeline.class);
        verify(timelineRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(SeverityLevel.P4);
    }
}
