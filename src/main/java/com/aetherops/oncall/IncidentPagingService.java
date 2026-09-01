package com.aetherops.oncall;

import com.aetherops.incident.model.IncidentSeverity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Entry point every incident-creation path (manual, log-grouped, monitoring-alert-ingested)
 * calls to trigger SMS/phone paging. Previously OnCallService.pageOnCall() was only ever
 * invoked from the manual Slack /page command — paging never fired automatically off a real
 * incident. AlertDeduplication gates repeat pages for the same flapping alert.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentPagingService {

    private final OnCallService onCallService;
    private final AlertDeduplication alertDeduplication;
    private final IncidentTimelineService timelineService;

    public void maybePage(UUID tenantId, UUID incidentId, String serviceId, String title, IncidentSeverity severity) {
        SeverityLevel level = SeverityLevel.fromIncidentSeverity(severity);
        if (!level.shouldPageImmediately()) {
            return;
        }

        DeduplicatedAlert dedup = alertDeduplication.record(
            new AlertDeduplication.Alert(tenantId, serviceId, title, level));

        if (dedup.getIncidentId() != null) {
            log.debug("Skipping page — already paged for this alert fingerprint incidentId={}", incidentId);
            return;
        }

        alertDeduplication.linkIncident(dedup, incidentId);
        onCallService.pageOnCall(tenantId, serviceId, title);
        timelineService.record(tenantId, incidentId, IncidentTimeline.TimelineEventType.ENGINEER_PAGED,
            level, "On-call engineer paged for " + serviceId);
    }
}
