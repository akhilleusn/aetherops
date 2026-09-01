package com.aetherops.statuspage.service;

import com.aetherops.branding.BrandingResponse;
import com.aetherops.branding.BrandingService;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.service.model.Service;
import com.aetherops.service.repository.ServiceRepository;
import com.aetherops.statuspage.model.*;
import com.aetherops.statuspage.repository.StatusPageIncidentUpdateRepository;
import com.aetherops.statuspage.repository.StatusPageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
@Slf4j
public class StatusPageService {

    private final StatusPageRepository statusPageRepository;
    private final StatusPageIncidentUpdateRepository updateRepository;
    private final ServiceRepository serviceRepository;
    private final IncidentRepository incidentRepository;
    private final BrandingService brandingService;

    @Transactional
    public StatusPage configure(UUID tenantId, CreateStatusPageRequest request) {
        if (!statusPageRepository.existsBySlug(request.getSlug())
                && statusPageRepository.findByTenantId(tenantId).isEmpty()) {
            StatusPage created = statusPageRepository.save(StatusPage.builder()
                .tenantId(tenantId).slug(request.getSlug()).name(request.getName()).build());
            log.info("Created status page tenantId={} slug={}", tenantId, request.getSlug());
            return created;
        }

        StatusPage existing = statusPageRepository.findByTenantId(tenantId)
            .orElseThrow(() -> new IllegalStateException("Slug '" + request.getSlug() + "' is already taken"));
        existing.setName(request.getName());
        existing.setSlug(request.getSlug());
        return statusPageRepository.save(existing);
    }

    @Transactional
    public StatusPageIncidentUpdate postUpdate(UUID tenantId, UUID incidentId, PostStatusPageUpdateRequest request) {
        Incident incident = incidentRepository.findById(incidentId)
            .filter(i -> tenantId.equals(i.getTenantId()))
            .orElseThrow(() -> new ResourceNotFoundException("Incident not found: " + incidentId));

        StatusPageIncidentUpdate saved = updateRepository.save(StatusPageIncidentUpdate.builder()
            .incidentId(incident.getId())
            .tenantId(tenantId)
            .status(request.getStatus())
            .message(request.getMessage())
            .build());

        log.info("Posted status page update incidentId={} status={}", incidentId, request.getStatus());
        return saved;
    }

    /**
     * Resolves the slug to a tenantId WITHOUT touching any RLS-protected table (status_pages
     * carries no RLS policy), so this is safe to call before TenantContext is set. The caller
     * (PublicStatusPageController) must set TenantContext to this value before calling
     * getPublicPage — that method reads the RLS-protected incidents table, and this endpoint
     * has no JWT to derive a tenant context from otherwise.
     */
    @Transactional(readOnly = true)
    public UUID resolveTenantIdForSlug(String slug) {
        return statusPageRepository.findBySlug(slug)
            .orElseThrow(() -> new ResourceNotFoundException("Status page not found: " + slug))
            .getTenantId();
    }

    @Transactional(readOnly = true)
    public PublicStatusPageResponse getPublicPage(String slug) {
        StatusPage page = statusPageRepository.findBySlug(slug)
            .orElseThrow(() -> new ResourceNotFoundException("Status page not found: " + slug));

        List<StatusPageIncidentUpdate> recentUpdates = updateRepository
            .findTop50ByTenantIdOrderByPostedAtDesc(page.getTenantId());

        Map<UUID, List<StatusPageIncidentUpdate>> updatesByIncident = recentUpdates.stream()
            .collect(Collectors.groupingBy(StatusPageIncidentUpdate::getIncidentId));

        Map<UUID, Incident> incidents = incidentRepository.findAllById(updatesByIncident.keySet()).stream()
            .collect(Collectors.toMap(Incident::getId, i -> i));

        List<PublicStatusPageResponse.PublicIncident> publicIncidents = new ArrayList<>();
        Map<String, ComponentStatus> serviceStatus = new HashMap<>();

        for (Map.Entry<UUID, List<StatusPageIncidentUpdate>> entry : updatesByIncident.entrySet()) {
            Incident incident = incidents.get(entry.getKey());
            if (incident == null) continue;

            boolean stillOpen = incident.getStatus() == IncidentStatus.OPEN
                || incident.getStatus() == IncidentStatus.INVESTIGATING;

            List<PublicStatusPageResponse.PublicUpdate> updates = entry.getValue().stream()
                .sorted(Comparator.comparing(StatusPageIncidentUpdate::getPostedAt))
                .map(u -> PublicStatusPageResponse.PublicUpdate.builder()
                    .status(u.getStatus()).message(u.getMessage()).postedAt(u.getPostedAt()).build())
                .toList();

            publicIncidents.add(PublicStatusPageResponse.PublicIncident.builder()
                .title(incident.getTitle())
                .resolved(!stillOpen)
                .updates(updates)
                .build());

            if (stillOpen && incident.getServiceId() != null) {
                ComponentStatus status = componentStatusFor(incident.getSeverity());
                serviceStatus.merge(incident.getServiceId(), status, StatusPageService::worseOf);
            }
        }

        publicIncidents.sort((a, b) -> {
            Instant aLatest = a.getUpdates().isEmpty() ? Instant.EPOCH : a.getUpdates().get(a.getUpdates().size() - 1).getPostedAt();
            Instant bLatest = b.getUpdates().isEmpty() ? Instant.EPOCH : b.getUpdates().get(b.getUpdates().size() - 1).getPostedAt();
            return bLatest.compareTo(aLatest);
        });

        List<Service> services = serviceRepository.findByTenantId(page.getTenantId());
        List<PublicStatusPageResponse.PublicComponent> components = services.stream()
            .map(s -> PublicStatusPageResponse.PublicComponent.builder()
                .name(s.getName())
                .status(serviceStatus.getOrDefault(s.getName(), ComponentStatus.OPERATIONAL))
                .build())
            .toList();

        ComponentStatus overall = components.stream()
            .map(PublicStatusPageResponse.PublicComponent::getStatus)
            .reduce(ComponentStatus.OPERATIONAL, StatusPageService::worseOf);

        PublicStatusPageResponse.Branding branding = brandingService.get(page.getTenantId())
            .map(this::toBranding)
            .orElse(null);

        return PublicStatusPageResponse.builder()
            .pageName(page.getName())
            .overallStatus(overall)
            .components(components)
            .incidents(publicIncidents)
            .branding(branding)
            .build();
    }

    private PublicStatusPageResponse.Branding toBranding(BrandingResponse b) {
        return PublicStatusPageResponse.Branding.builder()
            .companyName(b.getCompanyName())
            .logoUrl(b.getLogoUrl())
            .primaryColor(b.getPrimaryColor())
            .secondaryColor(b.getSecondaryColor())
            .build();
    }

    private ComponentStatus componentStatusFor(IncidentSeverity severity) {
        return switch (severity) {
            case CRITICAL -> ComponentStatus.MAJOR_OUTAGE;
            case HIGH -> ComponentStatus.PARTIAL_OUTAGE;
            case MEDIUM, LOW -> ComponentStatus.DEGRADED_PERFORMANCE;
        };
    }

    private static ComponentStatus worseOf(ComponentStatus a, ComponentStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
