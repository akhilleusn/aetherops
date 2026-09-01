package com.aetherops.sso;

import com.aetherops.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SsoConfigControllerTest {

    @Mock private SsoConfigService ssoConfigService;

    @InjectMocks
    private SsoConfigController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_delegatesToServiceAndReturnsResponse() {
        ConfigureSsoRequest request = new ConfigureSsoRequest("idp-entity", "https://idp/sso", "cert", true);
        TenantSsoConfigResponse serviceResponse = TenantSsoConfigResponse.builder().tenantId(tenantId).enabled(true).build();
        when(ssoConfigService.configure(tenantId, request)).thenReturn(serviceResponse);

        ApiResponse<TenantSsoConfigResponse> response = controller.configure(tenantId, request);

        assertThat(response.getData().isEnabled()).isTrue();
        verify(ssoConfigService).configure(tenantId, request);
    }

    @Test
    void get_delegatesToServiceAndReturnsResponse() {
        TenantSsoConfigResponse serviceResponse = TenantSsoConfigResponse.builder()
            .tenantId(tenantId).enabled(false).idpEntityId("idp-entity").build();
        when(ssoConfigService.get(tenantId)).thenReturn(serviceResponse);

        ApiResponse<TenantSsoConfigResponse> response = controller.get(tenantId);

        assertThat(response.getData().getIdpEntityId()).isEqualTo("idp-entity");
    }

    @Test
    void get_propagatesExceptionFromServiceWhenUnconfigured() {
        when(ssoConfigService.get(tenantId))
            .thenThrow(new com.aetherops.common.exception.ResourceNotFoundException("No SSO configuration for tenant: " + tenantId));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.get(tenantId))
            .isInstanceOf(com.aetherops.common.exception.ResourceNotFoundException.class);
    }
}
