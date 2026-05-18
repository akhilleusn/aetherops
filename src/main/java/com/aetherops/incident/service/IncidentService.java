package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.incident.repository.IncidentSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentService {

    private final IncidentRepository incidentRepository;

    @Transactional
    public IncidentResponse create(CreateIncidentRequest request) {
        Instant now = Instant.now();
        // Manual incidents get a random fingerprint so they are never auto-merged.
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
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public IncidentResponse findById(UUID id) {
        return incidentRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<IncidentResponse> findAll(
        UUID tenantId, IncidentStatus status, String serviceId, IncidentSeverity severity, Pageable pageable
    ) {
        Specification<Incident> spec = Specification
            .where(IncidentSpecification.hasTenant(tenantId))
            .and(IncidentSpecification.hasStatus(status))
            .and(IncidentSpecification.hasServiceId(serviceId))
            .and(IncidentSpecification.hasSeverity(severity));

        return incidentRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional
    public IncidentResponse update(UUID id, UpdateIncidentRequest request) {
        Incident incident = incidentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));

        if (request.getTitle() != null)       incident.setTitle(request.getTitle());
        if (request.getDescription() != null) incident.setDescription(request.getDescription());
        if (request.getSeverity() != null)    incident.setSeverity(request.getSeverity());
        if (request.getStatus() != null) {
            incident.setStatus(request.getStatus());
            if (request.getStatus() == IncidentStatus.RESOLVED && incident.getResolvedAt() == null) {
                incident.setResolvedAt(Instant.now());
            }
        }

        return toResponse(incidentRepository.save(incident));
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
