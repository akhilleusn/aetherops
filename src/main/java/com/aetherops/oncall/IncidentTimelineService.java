package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentTimelineService {

    private final IncidentTimelineRepository timelineRepository;

    // Both overloads need @Transactional, not just one: callers reach either one directly as a
    // cross-bean call (IncidentService/IncidentGroupingService/MonitoringAlertIngestionService/
    // SlackInteractivityController call the 4-arg overload; IncidentPagingService's async
    // maybePage calls the 5-arg overload directly), so whichever one is entered first must itself
    // trigger TenantContextInterceptor. The 4-arg overload's self-invocation of the 5-arg one is
    // then safe -- it runs inside the transaction the proxy already opened on entry.
    @Transactional
    public void record(UUID tenantId, UUID incidentId, IncidentTimeline.TimelineEventType eventType,
                        SeverityLevel severity, String detail) {
        timelineRepository.save(IncidentTimeline.builder()
            .tenantId(tenantId)
            .incidentId(incidentId)
            .eventType(eventType)
            .severity(severity)
            .detail(detail)
            .build());
    }

    @Transactional
    public void record(UUID tenantId, UUID incidentId, IncidentTimeline.TimelineEventType eventType,
                        IncidentSeverity incidentSeverity, String detail) {
        record(tenantId, incidentId, eventType, SeverityLevel.fromIncidentSeverity(incidentSeverity), detail);
    }

    // @Transactional for the same reason as the writes: incident_timeline has FORCE ROW LEVEL
    // SECURITY, and TenantContextInterceptor only sets app.tenant_id on entry to a method carrying
    // the annotation itself. Calling the repository directly from a controller would read with no
    // tenant set and silently return nothing.
    @Transactional(readOnly = true)
    public List<IncidentTimelineResponse> findForIncident(UUID tenantId, UUID incidentId) {
        return timelineRepository.findByTenantIdAndIncidentIdOrderByCreatedAtAsc(tenantId, incidentId).stream()
            .map(IncidentTimelineResponse::from)
            .toList();
    }
}
