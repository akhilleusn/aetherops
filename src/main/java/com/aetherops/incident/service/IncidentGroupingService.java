package com.aetherops.incident.service;

import com.aetherops.common.FingerprintUtil;
import com.aetherops.evidence.model.EvidenceItem;
import com.aetherops.evidence.model.EvidenceType;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentGroupingService {

    private final IncidentRepository      incidentRepository;
    private final EvidenceItemRepository  evidenceItemRepository;
    private final SlackNotifier           slackNotifier;
    private final IncidentCorrelationService correlationService;
    private final IncidentTimelineService timelineService;
    private final IncidentPagingService   pagingService;

    @Transactional
    public void findOrCreate(LogEvent event) {
        if (!shouldGroup(event.getLevel())) {
            return;
        }

        String fingerprint = buildFingerprint(event);

        incidentRepository
            .findByTenantIdAndFingerprintAndStatus(event.getTenantId(), fingerprint, IncidentStatus.OPEN)
            .ifPresentOrElse(
                existing -> updateExisting(existing, event),
                () -> correlationService
                        .correlate(event.getTenantId(), event.getServiceId(), fingerprint, event.getOccurredAt())
                        .ifPresentOrElse(
                            correlated -> attachEvidence(correlated, event),
                            () -> createNew(event, fingerprint)
                        )
            );
    }

    private boolean shouldGroup(LogLevel level) {
        return level == LogLevel.WARN || level == LogLevel.ERROR || level == LogLevel.FATAL;
    }

    private void createNew(LogEvent event, String fingerprint) {
        Incident incident = Incident.builder()
            .tenantId(event.getTenantId())
            .projectId(event.getProjectId())
            .title(buildTitle(event))
            .status(IncidentStatus.OPEN)
            .severity(initialSeverity(event.getLevel()))
            .serviceId(event.getServiceId())
            .environment(event.getEnvironment())
            .fingerprint(fingerprint)
            .eventCount(1)
            .firstSeenAt(event.getOccurredAt())
            .lastSeenAt(event.getOccurredAt())
            .latestEventId(event.getId())
            .build();

        Incident saved = incidentRepository.save(incident);
        log.info("Created incident id={} tenant={} service={} severity={} fingerprint={}",
            saved.getId(), saved.getTenantId(), saved.getServiceId(), saved.getSeverity(), fingerprint);

        timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.CREATED,
            saved.getSeverity(), "Incident created: " + saved.getTitle());

        if (saved.getSeverity().isAlertable()) {
            slackNotifier.notify(saved);
            pagingService.maybePage(saved.getTenantId(), saved.getId(), saved.getServiceId(),
                saved.getTitle(), saved.getSeverity());
        }

        attachEvidence(saved, event);
    }

    private void updateExisting(Incident incident, LogEvent event) {
        incident.setEventCount(incident.getEventCount() + 1);
        incident.setLastSeenAt(event.getOccurredAt());
        incident.setLatestEventId(event.getId());

        IncidentSeverity previousSeverity = incident.getSeverity();
        IncidentSeverity newSeverity = escalatedSeverity(previousSeverity, incident.getEventCount(), event.getLevel());
        incident.setSeverity(newSeverity);

        Incident saved = incidentRepository.save(incident);
        log.info("Updated incident id={} eventCount={} severity={}",
            saved.getId(), saved.getEventCount(), saved.getSeverity());

        if (newSeverity != previousSeverity) {
            timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.SEVERITY_CHANGED,
                saved.getSeverity(), "Severity escalated from " + previousSeverity + " to " + newSeverity);
        }

        if (newSeverity.isAlertable() && !previousSeverity.isAlertable()) {
            slackNotifier.notify(saved);
            pagingService.maybePage(saved.getTenantId(), saved.getId(), saved.getServiceId(),
                saved.getTitle(), newSeverity);
        }

        attachEvidence(saved, event);
    }

    private void attachEvidence(Incident incident, LogEvent event) {
        String summary = buildSummary(event.getMessage());
        EvidenceItem item = EvidenceItem.builder()
            .incidentId(incident.getId())
            .tenantId(incident.getTenantId())
            .logEventId(event.getId())
            .type(EvidenceType.LOG_EVENT)
            .sourceRef(event.getId().toString())
            .summary(summary)
            .level(event.getLevel().name())
            .timestamp(event.getOccurredAt())
            .build();

        evidenceItemRepository.save(item);
    }

    // Called after eventCount has already been incremented.
    private IncidentSeverity escalatedSeverity(IncidentSeverity current, int newEventCount, LogLevel level) {
        if (level == LogLevel.FATAL)                         return IncidentSeverity.CRITICAL;
        if (current == IncidentSeverity.CRITICAL)            return IncidentSeverity.CRITICAL;
        if (level == LogLevel.ERROR && newEventCount >= 3)   return IncidentSeverity.HIGH;
        if (current == IncidentSeverity.HIGH)                return IncidentSeverity.HIGH;
        return current;
    }

    private IncidentSeverity initialSeverity(LogLevel level) {
        return switch (level) {
            case FATAL -> IncidentSeverity.CRITICAL;
            case ERROR -> IncidentSeverity.MEDIUM;
            default    -> IncidentSeverity.LOW;
        };
    }

    private String buildTitle(LogEvent event) {
        String normalized = normalizeMessage(event.getMessage());
        String truncated = normalized.length() > 120 ? normalized.substring(0, 120) + "…" : normalized;
        return "[" + event.getLevel() + "] " + event.getServiceId() + ": " + truncated;
    }

    private String buildSummary(String maskedMessage) {
        if (maskedMessage == null) return null;
        return maskedMessage.length() > 200 ? maskedMessage.substring(0, 200) + "…" : maskedMessage;
    }

    private String buildFingerprint(LogEvent event) {
        String normalized = normalizeMessage(event.getMessage());
        String raw = event.getTenantId() + ":" + event.getServiceId() + ":" + event.getLevel() + ":" + normalized;
        return FingerprintUtil.sha256Hex(raw);
    }

    private String normalizeMessage(String message) {
        if (message == null) return "";
        return message
            .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>")
            .replaceAll("[0-9a-fA-F]{8,}", "<hex>")
            .replaceAll("\\d{4,}", "<n>")
            .toLowerCase()
            .trim();
    }
}
