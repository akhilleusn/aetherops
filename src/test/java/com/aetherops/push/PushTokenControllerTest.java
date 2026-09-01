package com.aetherops.push;

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
class PushTokenControllerTest {

    @Mock private PushNotificationService pushNotificationService;

    @InjectMocks
    private PushTokenController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @Test
    void register_delegatesToServiceWithRequestFields() {
        RegisterPushTokenRequest request = new RegisterPushTokenRequest(tenantId, "ExponentPushToken[abc]", "ios");
        PushDeviceToken saved = PushDeviceToken.builder()
            .id(UUID.randomUUID()).userId(userId).tenantId(tenantId)
            .expoPushToken("ExponentPushToken[abc]").platform("ios").build();
        when(pushNotificationService.registerToken(userId, tenantId, "ExponentPushToken[abc]", "ios")).thenReturn(saved);

        ApiResponse<PushDeviceToken> response = controller.register(userId, request);

        assertThat(response.getData().getExpoPushToken()).isEqualTo("ExponentPushToken[abc]");
        verify(pushNotificationService).registerToken(userId, tenantId, "ExponentPushToken[abc]", "ios");
    }

    @Test
    void register_nullPlatform_stillDelegatesToService() {
        RegisterPushTokenRequest request = new RegisterPushTokenRequest(tenantId, "ExponentPushToken[xyz]", null);
        when(pushNotificationService.registerToken(userId, tenantId, "ExponentPushToken[xyz]", null))
            .thenReturn(PushDeviceToken.builder().expoPushToken("ExponentPushToken[xyz]").build());

        controller.register(userId, request);

        verify(pushNotificationService).registerToken(userId, tenantId, "ExponentPushToken[xyz]", null);
    }

    @Test
    void register_returnsSavedTokenWithGeneratedId() {
        UUID generatedId = UUID.randomUUID();
        RegisterPushTokenRequest request = new RegisterPushTokenRequest(tenantId, "ExponentPushToken[abc]", "android");
        when(pushNotificationService.registerToken(userId, tenantId, "ExponentPushToken[abc]", "android"))
            .thenReturn(PushDeviceToken.builder().id(generatedId).expoPushToken("ExponentPushToken[abc]").platform("android").build());

        ApiResponse<PushDeviceToken> response = controller.register(userId, request);

        assertThat(response.getData().getId()).isEqualTo(generatedId);
    }
}
