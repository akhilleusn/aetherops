package com.aetherops.tenant;

import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.controller.OnboardingController;
import com.aetherops.tenant.model.OnboardingStatusResponse;
import com.aetherops.tenant.service.OnboardingStatusService;
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
class OnboardingControllerTest {

    @Mock private OnboardingStatusService onboardingStatusService;

    @InjectMocks
    private OnboardingController controller;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void status_delegatesToServiceAndWrapsResult() {
        OnboardingStatusResponse status = OnboardingStatusResponse.builder()
            .tenantId(tenantId).nextStep("CREATE_ADMIN_USER").build();
        when(onboardingStatusService.getStatus(tenantId)).thenReturn(status);

        ApiResponse<OnboardingStatusResponse> response = controller.status(tenantId);

        assertThat(response.getData().getNextStep()).isEqualTo("CREATE_ADMIN_USER");
        verify(onboardingStatusService).getStatus(tenantId);
    }
}
