package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IncidentTimelineService {

    private final IncidentTimelineRepository timelineRepository;

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

    public void record(UUID tenantId, UUID incidentId, IncidentTimeline.TimelineEventType eventType,
                        IncidentSeverity incidentSeverity, String detail) {
        record(tenantId, incidentId, eventType, SeverityLevel.fromIncidentSeverity(incidentSeverity), detail);
    }
}
