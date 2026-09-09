package com.aetherops.monitoring.service;

import com.aetherops.common.FingerprintUtil;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.oncall.IncidentPagingService;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.slack.IncidentSlackNotificationService;
import com.aetherops.suppression.AlertSuppressionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Alert-shaped ingestion (Datadog/Prometheus monitor state changes), separate from
 * {@link com.aetherops.incident.service.IncidentGroupingService} which groups raw log
 * lines. An "alert firing" already carries its own severity/status — there's no log
 * level to interpret — so it gets its own find-or-create-by-fingerprint path rather
 * than being force-fit into the log-event shape.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonitoringAlertIngestionService {

    private final IncidentRepository incidentRepository;
    private final IncidentSlackNotificationService slackNotificationService;
    private final IncidentTimelineService timelineService;
    private final IncidentPagingService pagingService;
    private final AlertSuppressionService alertSuppressionService;

    public record AlertSignal(
        UUID tenantId, UUID projectId, String serviceId, String alertName,
        String title, String description, IncidentSeverity severity,
        boolean firing, String environment, Instant occurredAt
    ) {}

    @Transactional
    public void ingest(AlertSignal signal) {
        if (alertSuppressionService.shouldSuppress(signal.tenantId(), signal.serviceId(), signal.alertName())) {
            log.debug("Suppressed monitoring alert tenantId={} service={} alertName={}",
                signal.tenantId(), signal.serviceId(), signal.alertName());
            return;
        }

        String fingerprint = buildFingerprint(signal);

        incidentRepository
            .findByTenantIdAndFingerprintAndStatus(signal.tenantId(), fingerprint, IncidentStatus.OPEN)
            .ifPresentOrElse(
                existing -> updateExisting(existing, signal),
                () -> {
                    if (signal.firing()) {
                        createNew(signal, fingerprint);
                    } else {
                        log.debug("Resolved alert with no matching open incident, ignoring fingerprint={}", fingerprint);
                    }
                }
            );
    }

    private void createNew(AlertSignal signal, String fingerprint) {
        Instant now = signal.occurredAt() != null ? signal.occurredAt() : Instant.now();

        Incident incident = Incident.builder()
            .tenantId(signal.tenantId())
            .projectId(signal.projectId())
            .title(signal.title())
            .description(signal.description())
            .status(IncidentStatus.OPEN)
            .severity(signal.severity())
            .serviceId(signal.serviceId())
            .environment(signal.environment())
            .fingerprint(fingerprint)
            .eventCount(1)
            .firstSeenAt(now)
            .lastSeenAt(now)
            .build();

        Incident saved = incidentRepository.save(incident);
        log.info("Created incident id={} from monitoring alert tenant={} service={} alertName={} severity={}",
            saved.getId(), signal.tenantId(), signal.serviceId(), signal.alertName(), signal.severity());

        timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.CREATED,
            saved.getSeverity(), "Incident created from monitoring alert: " + signal.alertName());

        if (saved.getSeverity().isAlertable()) {
            slackNotificationService.notify(saved);
            pagingService.maybePage(saved.getTenantId(), saved.getId(), saved.getServiceId(),
                saved.getTitle(), saved.getSeverity());
        }
    }

    private void updateExisting(Incident incident, AlertSignal signal) {
        if (!signal.firing()) {
            incident.resolve();
            incidentRepository.save(incident);
            log.info("Resolved incident id={} from monitoring alert alertName={}", incident.getId(), signal.alertName());
            timelineService.record(incident.getTenantId(), incident.getId(), IncidentTimeline.TimelineEventType.RESOLVED,
                incident.getSeverity(), "Resolved by monitoring alert: " + signal.alertName());
            return;
        }

        incident.setEventCount(incident.getEventCount() + 1);
        incident.setLastSeenAt(signal.occurredAt() != null ? signal.occurredAt() : Instant.now());

        IncidentSeverity previous = incident.getSeverity();
        if (signal.severity().ordinal() < previous.ordinal()) {
            incident.setSeverity(signal.severity());
        }

        Incident saved = incidentRepository.save(incident);
        log.info("Updated incident id={} eventCount={} from monitoring alert alertName={}",
            saved.getId(), saved.getEventCount(), signal.alertName());

        if (saved.getSeverity() != previous) {
            timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.SEVERITY_CHANGED,
                saved.getSeverity(), "Severity escalated from " + previous + " to " + saved.getSeverity()
                    + " by monitoring alert: " + signal.alertName());
        }

        if (saved.getSeverity().isAlertable() && !previous.isAlertable()) {
            slackNotificationService.notify(saved);
            pagingService.maybePage(saved.getTenantId(), saved.getId(), saved.getServiceId(),
                saved.getTitle(), saved.getSeverity());
        }
    }

    private String buildFingerprint(AlertSignal signal) {
        return computeFingerprint(signal.tenantId(), signal.serviceId(), signal.alertName());
    }

    /** Exposed so MonitoringAlertReplayGuard can key on the exact same fingerprint this
     *  service already uses for find-or-create, without needing a full AlertSignal (the
     *  replay check runs in the controller before severity/title/etc. are even decided). */
    public String computeFingerprint(UUID tenantId, String serviceId, String alertName) {
        String raw = tenantId + ":" + serviceId + ":" + alertName;
        return FingerprintUtil.sha256Hex(raw);
    }
}
