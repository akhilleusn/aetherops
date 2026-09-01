package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.CreateIncidentTemplateRequest;
import com.aetherops.incident.model.IncidentTemplate;
import com.aetherops.incident.model.IncidentTemplateResponse;
import com.aetherops.incident.repository.IncidentTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IncidentTemplateService {

    private final IncidentTemplateRepository templateRepository;

    @Transactional
    public IncidentTemplateResponse create(UUID tenantId, CreateIncidentTemplateRequest request) {
        IncidentTemplate saved = templateRepository.save(IncidentTemplate.builder()
            .tenantId(tenantId)
            .name(request.getName())
            .defaultTitle(request.getDefaultTitle())
            .defaultDescription(request.getDefaultDescription())
            .defaultSeverity(request.getDefaultSeverity())
            .defaultServiceId(request.getDefaultServiceId())
            .defaultTags(request.getDefaultTags())
            .build());

        log.info("Created incident template id={} name={} tenantId={}", saved.getId(), saved.getName(), tenantId);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<IncidentTemplateResponse> list(UUID tenantId) {
        return templateRepository.findByTenantIdIsNullOrTenantIdOrderByName(tenantId).stream()
            .map(this::toResponse)
            .toList();
    }

    /** Package-private lookup used by IncidentService when applying a template at creation time. */
    IncidentTemplate findVisible(UUID templateId, UUID tenantId) {
        return templateRepository.findVisibleToTenant(templateId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException("Incident template not found: " + templateId));
    }

    private IncidentTemplateResponse toResponse(IncidentTemplate template) {
        return IncidentTemplateResponse.builder()
            .id(template.getId())
            .tenantId(template.getTenantId())
            .name(template.getName())
            .defaultTitle(template.getDefaultTitle())
            .defaultDescription(template.getDefaultDescription())
            .defaultSeverity(template.getDefaultSeverity())
            .defaultServiceId(template.getDefaultServiceId())
            .defaultTags(template.getDefaultTags())
            .createdAt(template.getCreatedAt())
            .build();
    }
}
