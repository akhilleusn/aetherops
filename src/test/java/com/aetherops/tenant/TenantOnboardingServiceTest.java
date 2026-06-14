package com.aetherops.tenant;

import com.aetherops.auth.model.AuthResponse;
import com.aetherops.auth.model.RegisterRequest;
import com.aetherops.auth.service.AuthService;
import com.aetherops.tenant.model.CreateTenantRequest;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.TenantResponse;
import com.aetherops.tenant.service.TenantOnboardingService;
import com.aetherops.tenant.service.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantOnboardingServiceTest {

    @Mock
    private TenantService tenantService;

    @Mock
    private AuthService authService;

    @InjectMocks
    private TenantOnboardingService onboardingService;

    @Test
    void onboard_createsTenantAndAdminUser() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        TenantResponse tenantResponse = TenantResponse.builder()
                .id(tenantId)
                .name("Test Corp")
                .plan(Plan.STARTER)
                .build();

        AuthResponse authResponse = AuthResponse.builder()
                .userId(userId)
                .email("admin@test.com")
                .build();

        when(tenantService.create(any(CreateTenantRequest.class))).thenReturn(tenantResponse);
        when(authService.register(any(RegisterRequest.class))).thenReturn(authResponse);

        TenantOnboardingService.OnboardingResult result =
                onboardingService.onboard("Test Corp", Plan.STARTER, "admin@test.com", "password123");

        assertThat(result.tenantId()).isEqualTo(tenantId);
        assertThat(result.adminUserId()).isEqualTo(userId);
        verify(tenantService).create(any(CreateTenantRequest.class));
        verify(authService).register(any(RegisterRequest.class));
    }

    @Test
    void onboard_callsServicesInCorrectOrder() {
        UUID tenantId = UUID.randomUUID();
        TenantResponse tenantResponse = TenantResponse.builder()
                .id(tenantId).name("Corp").plan(Plan.STARTER).build();
        AuthResponse authResponse = AuthResponse.builder()
                .userId(UUID.randomUUID()).email("a@b.com").build();

        when(tenantService.create(any())).thenReturn(tenantResponse);
        when(authService.register(any())).thenReturn(authResponse);

        onboardingService.onboard("Corp", Plan.STARTER, "a@b.com", "pass");

        var order = inOrder(tenantService, authService);
        order.verify(tenantService).create(any());
        order.verify(authService).register(any());
    }
}