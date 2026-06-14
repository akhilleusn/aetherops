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

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentQueryService {

    private final IncidentRepository incidentRepository;

    @Transactional(readOnly = true)
    public IncidentResponse findById(UUID id) {
        return incidentRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<IncidentResponse> findAll(
            UUID tenantId,
            IncidentStatus status,
            String serviceId,
            IncidentSeverity severity,
            UUID projectId,
            Pageable pageable
    ) {
        Specification<Incident> spec = Specification
                .where(IncidentSpecification.hasTenant(tenantId))
                .and(IncidentSpecification.hasStatus(status))
                .and(IncidentSpecification.hasServiceId(serviceId))
                .and(IncidentSpecification.hasSeverity(severity))
                .and(IncidentSpecification.hasProjectId(projectId));

        return incidentRepository.findAll(spec, pageable).map(this::toResponse);
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
