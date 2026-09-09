package com.aetherops.push;

import com.aetherops.auth.security.CurrentUserContext;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1 Step E, item 1 (finding 7): userId and tenantId are no longer request input at
 * all — RegisterPushTokenRequest doesn't carry either field anymore. These tests confirm
 * the controller derives both from the authenticated principal (CurrentUserContext /
 * TenantContext), the same ThreadLocals JwtAuthFilter/CurrentUserContextFilter/
 * TenantContextFilter populate from the caller's own JWT on every real request.
 */
@ExtendWith(MockitoExtension.class)
class PushTokenControllerTest {

    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks
    private PushTokenController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        CurrentUserContext.set(userId);
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void register_delegatesToServiceWithAuthenticatedPrincipal() {
        RegisterPushTokenRequest request = new RegisterPushTokenRequest("ExponentPushToken[abc]", "ios");
        PushDeviceToken saved = PushDeviceToken.builder()
            .id(UUID.randomUUID()).userId(userId).tenantId(tenantId)
            .expoPushToken("ExponentPushToken[abc]").platform("ios").build();
        when(pushNotificationService.registerToken(userId, tenantId, "ExponentPushToken[abc]", "ios")).thenReturn(saved);

        ApiResponse<PushDeviceToken> response = controller.register(request);

        assertThat(response.getData().getExpoPushToken()).isEqualTo("ExponentPushToken[abc]");
        verify(pushNotificationService).registerToken(userId, tenantId, "ExponentPushToken[abc]", "ios");
    }

    @Test
    void register_ignoresWhicheverPrincipalIsCurrentlySet_neverAClientValue() {
        // There is no client-supplied userId/tenantId anywhere in RegisterPushTokenRequest
        // for this test to even attempt to smuggle a different identity through — that's
        // the fix. This test instead proves the controller reads the CURRENT ThreadLocal
        // values (i.e. whoever the request's JWT actually authenticated as), not some
        // value captured earlier or a constant.
        UUID differentUser = UUID.randomUUID();
        UUID differentTenant = UUID.randomUUID();
        CurrentUserContext.set(differentUser);
        TenantContext.set(differentTenant);

        RegisterPushTokenRequest request = new RegisterPushTokenRequest("ExponentPushToken[xyz]", null);
        when(pushNotificationService.registerToken(differentUser, differentTenant, "ExponentPushToken[xyz]", null))
            .thenReturn(PushDeviceToken.builder().expoPushToken("ExponentPushToken[xyz]").build());

        controller.register(request);

        verify(pushNotificationService).registerToken(differentUser, differentTenant, "ExponentPushToken[xyz]", null);
    }
}
