package com.aetherops.suppression;

import com.aetherops.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertSuppressionControllerTest {

    @Mock private AlertSuppressionService suppressionService;

    @InjectMocks
    private AlertSuppressionController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void mute_delegatesToServiceAndReturnsResponse() {
        MuteAlertRequest request = new MuteAlertRequest("db-svc", "high_cpu", null, "noisy", "alice");
        AlertSuppressionRuleResponse serviceResponse = AlertSuppressionRuleResponse.builder()
            .tenantId(tenantId).serviceId("db-svc").build();
        when(suppressionService.mute(tenantId, request)).thenReturn(serviceResponse);

        ApiResponse<AlertSuppressionRuleResponse> response = controller.mute(tenantId, request);

        assertThat(response.getData().getServiceId()).isEqualTo("db-svc");
    }

    @Test
    void list_delegatesToServiceAndReturnsActiveRules() {
        AlertSuppressionRuleResponse rule = AlertSuppressionRuleResponse.builder().tenantId(tenantId).build();
        when(suppressionService.listActive(tenantId)).thenReturn(List.of(rule));

        ApiResponse<List<AlertSuppressionRuleResponse>> response = controller.list(tenantId);

        assertThat(response.getData()).hasSize(1);
    }

    @Test
    void unmute_delegatesToService() {
        UUID ruleId = UUID.randomUUID();

        controller.unmute(tenantId, ruleId);

        verify(suppressionService).unmute(tenantId, ruleId);
    }
}
