package com.aetherops.branding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandingServiceTest {

    @Mock private TenantBrandingRepository brandingRepository;

    @InjectMocks
    private BrandingService brandingService;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_newTenant_createsBranding() {
        when(brandingRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(brandingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BrandingRequest request = new BrandingRequest("Acme Corp", "https://acme.com/logo.png", "#112233", "#445566");
        BrandingResponse response = brandingService.configure(tenantId, request);

        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.getCompanyName()).isEqualTo("Acme Corp");
        assertThat(response.getPrimaryColor()).isEqualTo("#112233");
    }

    @Test
    void configure_existingTenant_updatesInPlace() {
        TenantBranding existing = TenantBranding.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).companyName("Old Name").build();
        when(brandingRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(brandingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BrandingRequest request = new BrandingRequest("New Name", null, null, null);
        brandingService.configure(tenantId, request);

        ArgumentCaptor<TenantBranding> captor = ArgumentCaptor.forClass(TenantBranding.class);
        verify(brandingRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getCompanyName()).isEqualTo("New Name");
    }

    @Test
    void get_configuredTenant_returnsBranding() {
        when(brandingRepository.findByTenantId(tenantId)).thenReturn(Optional.of(
            TenantBranding.builder().tenantId(tenantId).companyName("Acme").build()));

        Optional<BrandingResponse> result = brandingService.get(tenantId);

        assertThat(result).isPresent();
        assertThat(result.get().getCompanyName()).isEqualTo("Acme");
    }

    @Test
    void get_unconfiguredTenant_returnsEmpty() {
        when(brandingRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());

        assertThat(brandingService.get(tenantId)).isEmpty();
    }

    @Test
    void configure_resubmittingWithNullFields_overwritesPreviousValues() {
        TenantBranding existing = TenantBranding.builder()
            .id(UUID.randomUUID()).tenantId(tenantId)
            .companyName("Acme").logoUrl("https://acme.com/logo.png")
            .primaryColor("#112233").secondaryColor("#445566").build();
        when(brandingRepository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(brandingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        BrandingResponse response = brandingService.configure(tenantId, new BrandingRequest(null, null, null, null));

        assertThat(response.getCompanyName()).isNull();
        assertThat(response.getLogoUrl()).isNull();
        assertThat(response.getPrimaryColor()).isNull();
    }

    @Test
    void configure_responseIncludesUpdatedAtFromSavedEntity() {
        when(brandingRepository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        TenantBranding saved = TenantBranding.builder()
            .tenantId(tenantId).companyName("Acme").updatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z")).build();
        when(brandingRepository.save(any())).thenReturn(saved);

        BrandingResponse response = brandingService.configure(tenantId, new BrandingRequest("Acme", null, null, null));

        assertThat(response.getUpdatedAt()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z"));
    }
}
