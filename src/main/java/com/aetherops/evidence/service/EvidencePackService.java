package com.aetherops.evidence.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.evidence.model.EvidenceItem;
import com.aetherops.evidence.model.EvidenceItemResponse;
import com.aetherops.evidence.model.IncidentEvidencePackResponse;
import com.aetherops.evidence.repository.EvidenceItemRepository;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EvidencePackService {

    private final IncidentRepository incidentRepository;
    private final EvidenceItemRepository evidenceItemRepository;

    @Transactional(readOnly = true)
    public IncidentEvidencePackResponse getEvidencePack(UUID incidentId, UUID requestingTenantId) {
        Incident incident = incidentRepository.findById(incidentId)
            .filter(i -> i.getTenantId().equals(requestingTenantId))
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        List<EvidenceItem> items =
            evidenceItemRepository.findByIncidentIdOrderByTimestampDesc(incidentId);

        return IncidentEvidencePackResponse.builder()
            .incidentId(incident.getId())
            .tenantId(incident.getTenantId())
            .projectId(incident.getProjectId())
            .serviceId(incident.getServiceId())
            .severity(incident.getSeverity())
            .status(incident.getStatus())
            .firstSeen(incident.getFirstSeenAt())
            .lastSeen(incident.getLastSeenAt())
            .totalEvidenceCount(items.size())
            .evidence(items.stream().map(this::toResponse).toList())
            .build();
    }

    private EvidenceItemResponse toResponse(EvidenceItem item) {
        return EvidenceItemResponse.builder()
            .id(item.getId())
            .incidentId(item.getIncidentId())
            .logEventId(item.getLogEventId())
            .type(item.getType())
            .sourceRef(item.getSourceRef())
            .summary(item.getSummary())
            .level(item.getLevel())
            .timestamp(item.getTimestamp())
            .build();
    }
}
