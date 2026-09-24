package com.aetherops.oncall;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class IncidentTimelineResponse {
    private UUID id;
    private UUID incidentId;
    private IncidentTimeline.TimelineEventType eventType;
    private SeverityLevel severity;
    private String detail;
    private Instant createdAt;

    static IncidentTimelineResponse from(IncidentTimeline entry) {
        return IncidentTimelineResponse.builder()
            .id(entry.getId())
            .incidentId(entry.getIncidentId())
            .eventType(entry.getEventType())
            .severity(entry.getSeverity())
            .detail(entry.getDetail())
            .createdAt(entry.getCreatedAt())
            .build();
    }
}
