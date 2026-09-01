package com.aetherops.cost;

import com.aetherops.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantCostSettingsControllerTest {

    @Mock private IncidentCostService costService;

    @InjectMocks
    private TenantCostSettingsController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_delegatesToServiceAndReturnsResponse() {
        TenantCostSettingsRequest request = new TenantCostSettingsRequest(25000);
        TenantCostSettingsResponse serviceResponse = TenantCostSettingsResponse.builder()
            .tenantId(tenantId).hourlyResponderRateCents(25000).build();
        when(costService.configureSettings(tenantId, 25000)).thenReturn(serviceResponse);

        ApiResponse<TenantCostSettingsResponse> response = controller.configure(tenantId, request);

        assertThat(response.getData().getHourlyResponderRateCents()).isEqualTo(25000);
    }

    @Test
    void get_delegatesToServiceAndReturnsResponse() {
        TenantCostSettingsResponse serviceResponse = TenantCostSettingsResponse.builder()
            .tenantId(tenantId).hourlyResponderRateCents(15000).build();
        when(costService.getSettings(tenantId)).thenReturn(serviceResponse);

        ApiResponse<TenantCostSettingsResponse> response = controller.get(tenantId);

        assertThat(response.getData().getTenantId()).isEqualTo(tenantId);
    }
}
