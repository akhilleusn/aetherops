package com.aetherops.branding;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandingControllerTest {

    @Mock private BrandingService brandingService;

    @InjectMocks
    private BrandingController controller;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(tenantId);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void configure_delegatesToServiceAndReturnsResponse() {
        BrandingRequest request = new BrandingRequest("Acme Corp", null, "#112233", null);
        BrandingResponse serviceResponse = BrandingResponse.builder().tenantId(tenantId).companyName("Acme Corp").build();
        when(brandingService.configure(tenantId, request)).thenReturn(serviceResponse);

        ApiResponse<BrandingResponse> response = controller.configure(tenantId, request);

        assertThat(response.getData().getCompanyName()).isEqualTo("Acme Corp");
    }

    @Test
    void get_configuredTenant_returnsBranding() {
        BrandingResponse serviceResponse = BrandingResponse.builder().tenantId(tenantId).companyName("Acme Corp").build();
        when(brandingService.get(tenantId)).thenReturn(Optional.of(serviceResponse));

        ApiResponse<BrandingResponse> response = controller.get(tenantId);

        assertThat(response.getData().getCompanyName()).isEqualTo("Acme Corp");
    }

    @Test
    void get_unconfiguredTenant_throwsResourceNotFound() {
        when(brandingService.get(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(tenantId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void get_unconfiguredTenant_exceptionMessageIncludesTenantId() {
        when(brandingService.get(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.get(tenantId))
            .hasMessageContaining(tenantId.toString());
    }
}
