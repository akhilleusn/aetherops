package com.aetherops.incident.service;

import com.aetherops.incident.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentCommandService {

    private final IncidentService incidentService;
    private final IncidentEventSourcingService eventSourcingService;

    @Transactional
    public IncidentResponse handle(CreateIncidentCommand cmd, String idempotencyKey) {
        CreateIncidentRequest request = new CreateIncidentRequest();
        request.setTenantId(cmd.getTenantId());
        request.setProjectId(cmd.getProjectId());
        request.setTitle(cmd.getTitle());
        request.setDescription(cmd.getDescription());
        request.setSeverity(cmd.getSeverity());
        request.setServiceId(cmd.getServiceId());
        request.setEnvironment(cmd.getEnvironment());

        IncidentResponse response = incidentService.create(request, idempotencyKey);

        eventSourcingService.appendEvent(
                response.getId(), response.getTenantId(),
                "INCIDENT_CREATED", response);

        log.info("Handled CreateIncidentCommand incidentId={} tenant={}", response.getId(), response.getTenantId());
        return response;
    }

    @Transactional
    public IncidentResponse handle(ResolveIncidentCommand cmd) {
        UpdateIncidentRequest request = new UpdateIncidentRequest();
        request.setStatus(IncidentStatus.RESOLVED);

        IncidentResponse response = incidentService.update(cmd.getIncidentId(), request);

        eventSourcingService.appendEvent(
                cmd.getIncidentId(), cmd.getTenantId(),
                "INCIDENT_RESOLVED", response);

        log.info("Handled ResolveIncidentCommand incidentId={} tenant={}", cmd.getIncidentId(), cmd.getTenantId());
        return response;
    }
}
