package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.event.IncidentCreatedEvent;
import com.aetherops.incident.event.IncidentEventPublisher;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IdempotencyKeyRepository;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.repository.IncidentSpecification;
import com.aetherops.slack.SlackNotifier;
import com.aetherops.webhook.WebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final SlackNotifier slackNotifier;
    private final IncidentEventPublisher eventPublisher;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final WebhookService webhookService;

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

        Instant now = Instant.now();
        String fingerprint = UUID.randomUUID().toString().replace("-", "");

        Incident incident = Incident.builder()
                .tenantId(request.getTenantId())
                .projectId(request.getProjectId())
                .title(request.getTitle())
                .description(request.getDescription())
                .status(IncidentStatus.OPEN)
                .severity(request.getSeverity())
                .serviceId(request.getServiceId())
                .environment(request.getEnvironment())
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

        if (saved.getSeverity().isAlertable()) {
            slackNotifier.notify(saved);
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
        return incidentRepository.findById(id)
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
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));

        IncidentSeverity previousSeverity = incident.getSeverity();

        if (request.getTitle() != null)       incident.setTitle(request.getTitle());
        if (request.getDescription() != null) incident.setDescription(request.getDescription());
        if (request.getSeverity() != null)    incident.setSeverity(request.getSeverity());
        if (request.getStatus() != null) {
            incident.setStatus(request.getStatus());
            if (request.getStatus() == IncidentStatus.RESOLVED && incident.getResolvedAt() == null) {
                incident.setResolvedAt(Instant.now());
            }
        }

        Incident saved = incidentRepository.save(incident);

        if (request.getSeverity() != null
                && saved.getSeverity().isAlertable()
                && !previousSeverity.isAlertable()) {
            slackNotifier.notify(saved);
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
                .build();
    }
    @Transactional
    public void softDelete(UUID id) {
        Incident incident = incidentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
        incident.setDeletedAt(Instant.now());
        incidentRepository.save(incident);
        log.info("Soft deleted incident id={}", id);
    }
}