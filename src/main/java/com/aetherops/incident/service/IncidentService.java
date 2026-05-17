package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.*;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
        Incident incident = Incident.builder()
            .title(request.getTitle())
            .description(request.getDescription())
            .status(IncidentStatus.OPEN)
            .severity(request.getSeverity())
            .serviceId(request.getServiceId())
            .environment(request.getEnvironment())
            .build();

        Incident saved = incidentRepository.save(incident);
        log.info("Created incident id={} service={} severity={}", saved.getId(), saved.getServiceId(), saved.getSeverity());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public IncidentResponse findById(UUID id) {
        return incidentRepository.findById(id)
            .map(this::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<IncidentResponse> findAll(IncidentStatus status, String serviceId, Pageable pageable) {
        if (status != null && serviceId != null) {
            return incidentRepository.findByStatusAndServiceId(status, serviceId, pageable).map(this::toResponse);
        }
        if (status != null) {
            return incidentRepository.findByStatus(status, pageable).map(this::toResponse);
        }
        if (serviceId != null) {
            return incidentRepository.findByServiceId(serviceId, pageable).map(this::toResponse);
        }
        return incidentRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional
    public IncidentResponse update(UUID id, UpdateIncidentRequest request) {
        Incident incident = incidentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + id));

        if (request.getTitle() != null) incident.setTitle(request.getTitle());
        if (request.getDescription() != null) incident.setDescription(request.getDescription());
        if (request.getSeverity() != null) incident.setSeverity(request.getSeverity());
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
            .title(incident.getTitle())
            .description(incident.getDescription())
            .status(incident.getStatus())
            .severity(incident.getSeverity())
            .serviceId(incident.getServiceId())
            .environment(incident.getEnvironment())
            .createdAt(incident.getCreatedAt())
            .updatedAt(incident.getUpdatedAt())
            .resolvedAt(incident.getResolvedAt())
            .build();
    }
}
