package com.aetherops.incident.service;

import com.aetherops.common.exception.IncidentValidationException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.event.IncidentCreatedEvent;
import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.repository.IncidentSpecification;
import com.aetherops.incident.severity.SeverityDetectionResult;
import com.aetherops.incident.severity.SeverityDetector;
import com.aetherops.oncall.IncidentPagingService;
import com.aetherops.oncall.IncidentTimeline;
import com.aetherops.oncall.IncidentTimelineService;
import com.aetherops.slack.IncidentSlackNotificationService;
import com.aetherops.tenant.TenantContext;
import com.aetherops.usage.model.UsageType;
import com.aetherops.usage.service.UsageLimitService;
import com.aetherops.webhook.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final IncidentSlackNotificationService incidentSlackNotificationService;
    private final IncidentEventPublisher eventPublisher;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final WebhookService webhookService;
    private final UsageLimitService usageLimitService;
    private final IncidentTemplateService templateService;
    private final SeverityDetector severityDetector;
    private final IncidentTimelineService timelineService;
    private final IncidentPagingService pagingService;

    @Transactional
    public IncidentResponse create(CreateIncidentRequest request, String idempotencyKey) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository
                    .findByIdempotencyKeyAndTenantIdAndResourceType(
                            idempotencyKey, request.getTenantId(), "INCIDENT");
            if (existing.isPresent() && existing.get().getResourceId() != null) {
                log.info("Idempotent request detected key={} returning cached response", idempotencyKey);
                return findById(existing.get().getResourceId());
            }
        }

        IncidentTemplate template = request.getTemplateId() != null
                ? templateService.findVisible(request.getTemplateId(), request.getTenantId())
                : null;

        String title       = firstNonBlank(request.getTitle(), template != null ? template.getDefaultTitle() : null);
        String description = firstNonNull(request.getDescription(), template != null ? template.getDefaultDescription() : null);
        String serviceId   = firstNonBlank(request.getServiceId(), template != null ? template.getDefaultServiceId() : null);
        IncidentSeverity severity = request.getSeverity() != null
                ? request.getSeverity()
                : (template != null ? template.getDefaultSeverity() : null);
        Map<String, String> tags = request.getTags() != null
                ? request.getTags()
                : (template != null ? template.getDefaultTags() : null);

        if (title == null || title.isBlank()) {
            throw new IncidentValidationException("title is required (directly, or via a template with a defaultTitle)");
        }
        if (serviceId == null || serviceId.isBlank()) {
            throw new IncidentValidationException("serviceId is required (directly, or via a template with a defaultServiceId)");
        }

        if (severity == null) {
            SeverityDetectionResult detected = severityDetector.detect(title, description);
            severity = detected.getPredictedSeverity();
            log.info("Auto-detected severity={} confidence={} reasoning=\"{}\" for incident title=\"{}\"",
                    severity, detected.getConfidenceScore(), detected.getReasoning(), title);
        }

        usageLimitService.checkAndIncrement(request.getTenantId(), UsageType.INCIDENT_CREATION);

        Instant now = Instant.now();
        String fingerprint = UUID.randomUUID().toString().replace("-", "");

        Incident incident = Incident.builder()
                .tenantId(request.getTenantId())
                .projectId(request.getProjectId())
                .title(title)
                .description(description)
                .status(IncidentStatus.OPEN)
                .severity(severity)
                .serviceId(serviceId)
                .environment(request.getEnvironment())
                .tags(tags)
                .fingerprint(fingerprint)
                .eventCount(1)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();

        Incident saved = incidentRepository.save(incident);
        log.info("Created incident id={} tenant={} service={} severity={}",
                saved.getId(), saved.getTenantId(), saved.getServiceId(), saved.getSeverity());

        eventPublisher.publish(IncidentCreatedEvent.builder()
                .incidentId(saved.getId())
                .tenantId(saved.getTenantId())
                .severity(saved.getSeverity().name())
                .title(saved.getTitle())
                .occurredAt(saved.getFirstSeenAt())
                .build());

        timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.CREATED,
                saved.getSeverity(), "Incident created: " + saved.getTitle());

        if (saved.getSeverity().isAlertable()) {
            incidentSlackNotificationService.notify(saved);
            pagingService.maybePage(saved.getTenantId(), saved.getId(), saved.getServiceId(),
                    saved.getTitle(), saved.getSeverity());
        }

        IncidentResponse response = toResponse(saved);
        webhookService.deliver(saved.getTenantId(), "incident.created", response);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyKeyRepository.save(IdempotencyKey.builder()
                    .idempotencyKey(idempotencyKey)
                    .tenantId(request.getTenantId())
                    .resourceType("INCIDENT")
                    .resourceId(saved.getId())
                    .build());
        }

        return response;
    }

    @Transactional(readOnly = true)
    public IncidentResponse findById(UUID id) {
        return incidentRepository.findByIdAndTenantId(id, TenantContext.get())
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<IncidentResponse> findAll(
            UUID tenantId, IncidentStatus status, String serviceId,
            IncidentSeverity severity, UUID projectId, Pageable pageable
    ) {
        Specification<Incident> spec = Specification
                .where(IncidentSpecification.hasTenant(tenantId))
                .and(IncidentSpecification.hasStatus(status))
                .and(IncidentSpecification.hasServiceId(serviceId))
                .and(IncidentSpecification.hasSeverity(severity))
                .and(IncidentSpecification.hasProjectId(projectId));

        return incidentRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public IncidentResponse update(UUID id, UpdateIncidentRequest request) {
        Incident incident = incidentRepository.findByIdAndTenantId(id, TenantContext.get())
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));

        IncidentSeverity previousSeverity = incident.getSeverity();

        boolean justResolved = request.getStatus() == IncidentStatus.RESOLVED && incident.getResolvedAt() == null;
        boolean justClosed   = request.getStatus() == IncidentStatus.CLOSED && incident.getStatus() != IncidentStatus.CLOSED;

        if (request.getTitle() != null)       incident.setTitle(request.getTitle());
        if (request.getDescription() != null) incident.setDescription(request.getDescription());
        if (request.getSeverity() != null)    incident.setSeverity(request.getSeverity());
        if (request.getTags() != null)        incident.setTags(request.getTags());
        if (request.getStatus() != null) {
            incident.setStatus(request.getStatus());
            if (justResolved) {
                incident.setResolvedAt(Instant.now());
            }
            if (request.getStatus() == IncidentStatus.INVESTIGATING && incident.getAcknowledgedAt() == null) {
                incident.setAcknowledgedAt(Instant.now());
            }
        }

        Incident saved = incidentRepository.save(incident);

        if (request.getSeverity() != null && !request.getSeverity().equals(previousSeverity)) {
            timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.SEVERITY_CHANGED,
                    saved.getSeverity(), "Severity changed from " + previousSeverity + " to " + saved.getSeverity());
        }
        if (justResolved) {
            timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.RESOLVED,
                    saved.getSeverity(), "Incident resolved");
        }
        if (justClosed) {
            timelineService.record(saved.getTenantId(), saved.getId(), IncidentTimeline.TimelineEventType.CLOSED,
                    saved.getSeverity(), "Incident closed");
        }

        if (request.getSeverity() != null
                && saved.getSeverity().isAlertable()
                && !previousSeverity.isAlertable()) {
            incidentSlackNotificationService.notify(saved);
            pagingService.maybePage(saved.getTenantId(), saved.getId(), saved.getServiceId(),
                    saved.getTitle(), saved.getSeverity());
        }

        return toResponse(saved);
    }

    private IncidentResponse toResponse(Incident incident) {
        return IncidentResponse.builder()
                .id(incident.getId())
                .tenantId(incident.getTenantId())
                .projectId(incident.getProjectId())
                .title(incident.getTitle())
                .description(incident.getDescription())
                .status(incident.getStatus())
                .severity(incident.getSeverity())
                .serviceId(incident.getServiceId())
                .environment(incident.getEnvironment())
                .fingerprint(incident.getFingerprint())
                .eventCount(incident.getEventCount())
                .firstSeenAt(incident.getFirstSeenAt())
                .lastSeenAt(incident.getLastSeenAt())
                .latestEventId(incident.getLatestEventId())
                .createdAt(incident.getCreatedAt())
                .updatedAt(incident.getUpdatedAt())
                .resolvedAt(incident.getResolvedAt())
                .tags(incident.getTags())
                .build();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    @Transactional
    public void softDelete(UUID id) {
        Incident incident = incidentRepository.findByIdAndTenantId(id, TenantContext.get())
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
        incident.setDeletedAt(Instant.now());
        incidentRepository.save(incident);
        log.info("Soft deleted incident id={}", id);
    }
}