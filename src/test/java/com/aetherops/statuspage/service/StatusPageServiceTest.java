package com.aetherops.statuspage.service;

import com.aetherops.branding.BrandingService;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.service.model.Service;
import com.aetherops.service.model.ServiceEnvironment;
import com.aetherops.service.repository.ServiceRepository;
import com.aetherops.statuspage.model.*;
import com.aetherops.statuspage.repository.StatusPageIncidentUpdateRepository;
import com.aetherops.statuspage.repository.StatusPageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StatusPageServiceTest {

    @Mock private StatusPageRepository statusPageRepository;
    @Mock private StatusPageIncidentUpdateRepository updateRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private IncidentRepository incidentRepository;
    @Mock private BrandingService brandingService;

    @InjectMocks
    private StatusPageService statusPageService;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_newTenant_createsPage() {
        when(statusPageRepository.existsBySlug("acme")).thenReturn(false);
        when(statusPageRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(statusPageRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StatusPage page = statusPageService.configure(tenantId, new CreateStatusPageRequest("Acme Status", "acme"));

        assertThat(page.getSlug()).isEqualTo("acme");
        assertThat(page.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void resolveTenantIdForSlug_unknownSlug_throwsResourceNotFound() {
        when(statusPageRepository.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statusPageService.resolveTenantIdForSlug("missing"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void postUpdate_unknownIncident_throwsResourceNotFound() {
        UUID incidentId = UUID.randomUUID();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> statusPageService.postUpdate(
            tenantId, incidentId, new PostStatusPageUpdateRequest(StatusPageUpdateStatus.INVESTIGATING, "msg")))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void postUpdate_incidentFromDifferentTenant_throwsResourceNotFound() {
        UUID incidentId = UUID.randomUUID();
        Incident incident = Incident.builder().id(incidentId).tenantId(UUID.randomUUID()).build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> statusPageService.postUpdate(
            tenantId, incidentId, new PostStatusPageUpdateRequest(StatusPageUpdateStatus.INVESTIGATING, "msg")))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void postUpdate_validIncident_savesUpdate() {
        UUID incidentId = UUID.randomUUID();
        Incident incident = Incident.builder().id(incidentId).tenantId(tenantId).build();
        when(incidentRepository.findById(incidentId)).thenReturn(Optional.of(incident));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StatusPageIncidentUpdate saved = statusPageService.postUpdate(
            tenantId, incidentId, new PostStatusPageUpdateRequest(StatusPageUpdateStatus.IDENTIFIED, "We found it"));

        assertThat(saved.getStatus()).isEqualTo(StatusPageUpdateStatus.IDENTIFIED);
        assertThat(saved.getMessage()).isEqualTo("We found it");
    }

    // ── getPublicPage ────────────────────────────────────────────────────

    private StatusPage page() {
        return StatusPage.builder().id(UUID.randomUUID()).tenantId(tenantId).slug("acme").name("Acme Status").build();
    }

    @Test
    void getPublicPage_unknownSlug_throwsResourceNotFound() {
        when(statusPageRepository.findBySlug("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> statusPageService.getPublicPage("missing"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getPublicPage_noIncidentUpdates_allComponentsOperational() {
        when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()));
        when(updateRepository.findTop50ByTenantIdOrderByPostedAtDesc(tenantId)).thenReturn(List.of());
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of(
            Service.builder().name("api-svc").environment(ServiceEnvironment.PRODUCTION).build()));

        PublicStatusPageResponse response = statusPageService.getPublicPage("acme");

        assertThat(response.getOverallStatus()).isEqualTo(ComponentStatus.OPERATIONAL);
        assertThat(response.getComponents()).hasSize(1);
        assertThat(response.getComponents().get(0).getStatus()).isEqualTo(ComponentStatus.OPERATIONAL);
        assertThat(response.getIncidents()).isEmpty();
    }

    @Test
    void getPublicPage_openCriticalIncidentWithUpdate_marksComponentAsMajorOutage() {
        UUID incidentId = UUID.randomUUID();
        Incident incident = Incident.builder()
            .id(incidentId).tenantId(tenantId).title("DB down")
            .status(IncidentStatus.OPEN).severity(IncidentSeverity.CRITICAL).serviceId("db-svc").build();
        StatusPageIncidentUpdate update = StatusPageIncidentUpdate.builder()
            .incidentId(incidentId).tenantId(tenantId)
            .status(StatusPageUpdateStatus.INVESTIGATING).message("Looking into it")
            .build();

        when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()));
        when(updateRepository.findTop50ByTenantIdOrderByPostedAtDesc(tenantId)).thenReturn(List.of(update));
        when(incidentRepository.findAllById(any())).thenReturn(List.of(incident));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of(
            Service.builder().name("db-svc").environment(ServiceEnvironment.PRODUCTION).build()));

        PublicStatusPageResponse response = statusPageService.getPublicPage("acme");

        assertThat(response.getOverallStatus()).isEqualTo(ComponentStatus.MAJOR_OUTAGE);
        assertThat(response.getComponents().get(0).getStatus()).isEqualTo(ComponentStatus.MAJOR_OUTAGE);
        assertThat(response.getIncidents()).hasSize(1);
        assertThat(response.getIncidents().get(0).isResolved()).isFalse();
        assertThat(response.getIncidents().get(0).getUpdates()).hasSize(1);
    }

    @Test
    void getPublicPage_resolvedIncident_doesNotAffectComponentStatus() {
        UUID incidentId = UUID.randomUUID();
        Incident incident = Incident.builder()
            .id(incidentId).tenantId(tenantId).title("DB blip")
            .status(IncidentStatus.RESOLVED).severity(IncidentSeverity.CRITICAL).serviceId("db-svc").build();
        StatusPageIncidentUpdate update = StatusPageIncidentUpdate.builder()
            .incidentId(incidentId).tenantId(tenantId)
            .status(StatusPageUpdateStatus.RESOLVED).message("Fixed")
            .build();

        when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()));
        when(updateRepository.findTop50ByTenantIdOrderByPostedAtDesc(tenantId)).thenReturn(List.of(update));
        when(incidentRepository.findAllById(any())).thenReturn(List.of(incident));
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of(
            Service.builder().name("db-svc").environment(ServiceEnvironment.PRODUCTION).build()));

        PublicStatusPageResponse response = statusPageService.getPublicPage("acme");

        assertThat(response.getOverallStatus()).isEqualTo(ComponentStatus.OPERATIONAL);
        assertThat(response.getIncidents().get(0).isResolved()).isTrue();
    }

    @Test
    void getPublicPage_incidentWithoutAnyUpdate_neverAppearsPublicly() {
        // No StatusPageIncidentUpdate rows means the incident was never opted into the public page,
        // regardless of how severe or open it is internally.
        when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()));
        when(updateRepository.findTop50ByTenantIdOrderByPostedAtDesc(tenantId)).thenReturn(List.of());
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        PublicStatusPageResponse response = statusPageService.getPublicPage("acme");

        assertThat(response.getIncidents()).isEmpty();
        assertThat(response.getOverallStatus()).isEqualTo(ComponentStatus.OPERATIONAL);
    }

    @Test
    void getPublicPage_noBrandingConfigured_brandingIsNull() {
        when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()));
        when(updateRepository.findTop50ByTenantIdOrderByPostedAtDesc(tenantId)).thenReturn(List.of());
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());

        PublicStatusPageResponse response = statusPageService.getPublicPage("acme");

        assertThat(response.getBranding()).isNull();
    }

    @Test
    void getPublicPage_brandingConfigured_includesItInResponse() {
        when(statusPageRepository.findBySlug("acme")).thenReturn(Optional.of(page()));
        when(updateRepository.findTop50ByTenantIdOrderByPostedAtDesc(tenantId)).thenReturn(List.of());
        when(serviceRepository.findByTenantId(tenantId)).thenReturn(List.of());
        when(brandingService.get(tenantId)).thenReturn(Optional.of(
            com.aetherops.branding.BrandingResponse.builder()
                .tenantId(tenantId).companyName("Acme Corp").logoUrl("https://acme.com/logo.png")
                .primaryColor("#112233").secondaryColor("#445566").build()));

        PublicStatusPageResponse response = statusPageService.getPublicPage("acme");

        assertThat(response.getBranding()).isNotNull();
        assertThat(response.getBranding().getCompanyName()).isEqualTo("Acme Corp");
        assertThat(response.getBranding().getPrimaryColor()).isEqualTo("#112233");
    }
}
