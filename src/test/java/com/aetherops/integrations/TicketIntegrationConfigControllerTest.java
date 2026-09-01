package com.aetherops.integrations;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.integrations.controller.TicketIntegrationConfigController;
import com.aetherops.integrations.model.ConfigureTicketIntegrationRequest;
import com.aetherops.integrations.model.TicketIntegrationResponse;
import com.aetherops.integrations.model.TicketProvider;
import com.aetherops.integrations.service.TicketIntegrationConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketIntegrationConfigControllerTest {

    @Mock private TicketIntegrationConfigService configService;

    @InjectMocks
    private TicketIntegrationConfigController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_delegatesToServiceAndNeverExposesToken() throws Exception {
        ConfigureTicketIntegrationRequest request = new ConfigureTicketIntegrationRequest(
            TicketProvider.JIRA, "https://acme.atlassian.net", "OPS", "bot@acme.com", "plain-token");
        TicketIntegrationResponse serviceResponse = TicketIntegrationResponse.builder()
            .tenantId(tenantId).provider(TicketProvider.JIRA).baseUrl("https://acme.atlassian.net").enabled(true).build();
        when(configService.configure(tenantId, request)).thenReturn(serviceResponse);

        ApiResponse<TicketIntegrationResponse> response = controller.configure(tenantId, request);

        assertThat(response.getData().getProvider()).isEqualTo(TicketProvider.JIRA);
        // TicketIntegrationResponse has no token-shaped field at all, by design — confirm that stays true.
        boolean hasTokenField = java.util.Arrays.stream(TicketIntegrationResponse.class.getDeclaredFields())
            .map(Field::getName)
            .anyMatch(name -> name.toLowerCase().contains("token"));
        assertThat(hasTokenField).isFalse();
    }

    @Test
    void get_delegatesToService() {
        TicketIntegrationResponse serviceResponse = TicketIntegrationResponse.builder()
            .tenantId(tenantId).provider(TicketProvider.SERVICENOW).build();
        when(configService.get(tenantId, TicketProvider.SERVICENOW)).thenReturn(serviceResponse);

        ApiResponse<TicketIntegrationResponse> response = controller.get(tenantId, TicketProvider.SERVICENOW);

        assertThat(response.getData().getProvider()).isEqualTo(TicketProvider.SERVICENOW);
    }

    @Test
    void delete_delegatesToService() {
        controller.delete(tenantId, TicketProvider.JIRA);

        verify(configService).delete(tenantId, TicketProvider.JIRA);
    }
}
