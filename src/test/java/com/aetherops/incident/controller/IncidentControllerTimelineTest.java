package com.aetherops.incident.controller;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.service.IncidentService;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineResponse;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IncidentControllerTimelineTest {

    @Mock private IncidentService         incidentService;
    @Mock private IncidentTimelineService incidentTimelineService;

    @InjectMocks
    private IncidentController controller;

    private final UUID tenantId   = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();

    @BeforeEach
    void setTenant() {
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void getTimeline_returnsEntriesForOwnIncident() {
        IncidentTimelineResponse created = IncidentTimelineResponse.builder()
            .incidentId(incidentId)
            .eventType(IncidentTimeline.TimelineEventType.CREATED)
            .build();
        when(incidentTimelineService.findForIncident(tenantId, incidentId)).thenReturn(List.of(created));

        var response = controller.getTimeline(incidentId, tenantId);

        assertThat(response.getData()).containsExactly(created);
        verify(incidentService).findById(incidentId);
    }

    // Unknown or other-tenant incident: 404, not an empty timeline
    @Test
    void getTimeline_unknownIncident_throwsNotFoundWithoutReadingTimeline() {
        when(incidentService.findById(incidentId)).thenThrow(new ResourceNotFoundException("Incident not found: " + incidentId));

        assertThatThrownBy(() -> controller.getTimeline(incidentId, tenantId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(incidentTimelineService, never()).findForIncident(any(), any());
    }

    // Caller's JWT tenant must match the tenantId they pass
    @Test
    void getTimeline_otherTenantId_isRejected() {
        assertThatThrownBy(() -> controller.getTimeline(incidentId, UUID.randomUUID()))
            .isInstanceOf(RuntimeException.class);
        verify(incidentTimelineService, never()).findForIncident(any(), any());
    }
}
