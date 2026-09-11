package com.aetherops.push;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock private PushDeviceTokenRepository tokenRepository;
    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private PushNotificationService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.build()).thenReturn(mockRestClient);
        service = new PushNotificationService(tokenRepository, builder);
    }

    @SuppressWarnings("unchecked")
    private RestClient.RequestBodyUriSpec stubPostChain() {
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(mockRestClient.post()).thenReturn(bodySpec);
        return bodySpec;
    }

    @Test
    void registerToken_newToken_savesIt() {
        UUID userId = UUID.randomUUID();
        when(tokenRepository.findByTenantIdAndExpoPushToken(tenantId, "ExponentPushToken[abc]")).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PushDeviceToken result = service.registerToken(userId, tenantId, "ExponentPushToken[abc]", "ios");

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getPlatform()).isEqualTo("ios");
    }

    @Test
    void registerToken_existingToken_updatesUserAndTenant() {
        UUID oldUserId = UUID.randomUUID();
        UUID newUserId = UUID.randomUUID();
        PushDeviceToken existing = PushDeviceToken.builder()
            .id(UUID.randomUUID()).userId(oldUserId).tenantId(tenantId)
            .expoPushToken("ExponentPushToken[abc]").platform("ios").build();
        when(tokenRepository.findByTenantIdAndExpoPushToken(tenantId, "ExponentPushToken[abc]")).thenReturn(Optional.of(existing));
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PushDeviceToken result = service.registerToken(newUserId, tenantId, "ExponentPushToken[abc]", "android");

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getUserId()).isEqualTo(newUserId);
        assertThat(result.getPlatform()).isEqualTo("android");
    }

    // The bug this composite-key lookup fixes: registering the same physical device's token
    // under a DIFFERENT tenant must never be mistaken for "this tenant's existing
    // registration" -- confirms the lookup key includes tenantId, not just the token.
    @Test
    void registerToken_sameTokenDifferentTenant_isTreatedAsANewRegistration_notTheOtherTenants() {
        UUID otherTenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(tokenRepository.findByTenantIdAndExpoPushToken(tenantId, "ExponentPushToken[shared]")).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PushDeviceToken result = service.registerToken(userId, tenantId, "ExponentPushToken[shared]", "ios");

        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getTenantId()).isNotEqualTo(otherTenantId);
        verify(tokenRepository, never()).findByTenantIdAndExpoPushToken(eq(otherTenantId), any());
    }

    @Test
    void sendPageAlert_noRegisteredDevices_doesNotCallExpo() {
        when(tokenRepository.findByTenantId(tenantId)).thenReturn(List.of());

        boolean result = service.sendPageAlert(tenantId, "title", "body");

        assertThat(result).isFalse();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void sendPageAlert_registeredDevices_postsToExpoForEachToken() {
        PushDeviceToken t1 = PushDeviceToken.builder().expoPushToken("token-1").build();
        PushDeviceToken t2 = PushDeviceToken.builder().expoPushToken("token-2").build();
        when(tokenRepository.findByTenantId(tenantId)).thenReturn(List.of(t1, t2));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        boolean result = service.sendPageAlert(tenantId, "🚨 CRITICAL incident on db-svc", "DB down");

        assertThat(result).isTrue();
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0)).containsEntry("to", "token-1");
        assertThat(captor.getValue().get(0)).containsEntry("body", "DB down");
    }

    // Step G1 (see STEP_REPORT.md): sendPageAlert used to be void and swallow this exception
    // with no signal to the caller at all -- same shape of problem as the Twilio bug this batch
    // fixes. Now returns false so a caller (e.g. EscalationService's SMS->push->Slack fallback)
    // can tell push didn't deliver either.
    @Test
    void sendPageAlert_expoUnreachable_returnsFalseNotThrow() {
        when(tokenRepository.findByTenantId(tenantId)).thenReturn(
            List.of(PushDeviceToken.builder().expoPushToken("token-1").build()));
        when(mockRestClient.post()).thenThrow(new RuntimeException("network error"));

        boolean result = service.sendPageAlert(tenantId, "title", "body");

        assertThat(result).isFalse();
    }

    @Test
    void registerToken_nullPlatform_savesWithNullPlatform() {
        UUID userId = UUID.randomUUID();
        when(tokenRepository.findByTenantIdAndExpoPushToken(tenantId, "ExponentPushToken[xyz]")).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PushDeviceToken result = service.registerToken(userId, tenantId, "ExponentPushToken[xyz]", null);

        assertThat(result.getPlatform()).isNull();
    }

    @Test
    void sendPageAlert_messageBodyIncludesAllRegisteredTokens() {
        PushDeviceToken t1 = PushDeviceToken.builder().expoPushToken("token-1").build();
        PushDeviceToken t2 = PushDeviceToken.builder().expoPushToken("token-2").build();
        PushDeviceToken t3 = PushDeviceToken.builder().expoPushToken("token-3").build();
        when(tokenRepository.findByTenantId(tenantId)).thenReturn(List.of(t1, t2, t3));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.sendPageAlert(tenantId, "title", "body");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue()).extracting(m -> m.get("to"))
            .containsExactlyInAnyOrder("token-1", "token-2", "token-3");
    }

    @Test
    void sendPageAlert_messagesUseHighPriorityAndDefaultSound() {
        when(tokenRepository.findByTenantId(tenantId)).thenReturn(
            List.of(PushDeviceToken.builder().expoPushToken("token-1").build()));
        RestClient.RequestBodyUriSpec bodySpec = stubPostChain();

        service.sendPageAlert(tenantId, "title", "body");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(bodySpec).body(captor.capture());
        assertThat(captor.getValue().get(0)).containsEntry("priority", "high");
        assertThat(captor.getValue().get(0)).containsEntry("sound", "default");
    }
}
