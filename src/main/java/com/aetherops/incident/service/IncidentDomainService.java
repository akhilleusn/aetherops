package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.event.DomainEventPublisher;
import com.aetherops.incident.event.IncidentResolvedDomainEvent;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentDomainService {

    private final IncidentRepository incidentRepository;
    private final IncidentEventSourcingService eventSourcingService;
    private final DomainEventPublisher domainEventPublisher;

    @Transactional
    public IncidentResponse resolve(UUID incidentId, UUID tenantId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        incident.resolve();
        Incident saved = incidentRepository.save(incident);

        eventSourcingService.appendEvent(incidentId, tenantId, "INCIDENT_RESOLVED",
                java.util.Map.of("resolvedAt", saved.getResolvedAt().toString()));

        domainEventPublisher.publish(IncidentResolvedDomainEvent.of(incidentId, tenantId, saved.getResolvedAt()));

        log.info("Domain resolved incidentId={} tenant={}", incidentId, tenantId);
        return toResponse(saved);
    }

    @Transactional
    public IncidentResponse escalate(UUID incidentId, UUID tenantId, IncidentSeverity newSeverity) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        IncidentSeverity previous = incident.getSeverity();
        incident.escalate(newSeverity);
        Incident saved = incidentRepository.save(incident);

        eventSourcingService.appendEvent(incidentId, tenantId, "INCIDENT_ESCALATED",
                java.util.Map.of("from", previous.name(), "to", newSeverity.name()));

        log.info("Domain escalated incidentId={} from={} to={}", incidentId, previous, newSeverity);
        return toResponse(saved);
    }

    @Transactional
    public IncidentResponse reopen(UUID incidentId, UUID tenantId) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        incident.reopen();
        Incident saved = incidentRepository.save(incident);

        eventSourcingService.appendEvent(incidentId, tenantId, "INCIDENT_REOPENED",
                java.util.Map.of("reopenedAt", Instant.now().toString()));

        log.info("Domain reopened incidentId={} tenant={}", incidentId, tenantId);
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
}
