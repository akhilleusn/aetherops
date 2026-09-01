package com.aetherops.calendar;

import com.aetherops.auth.model.Role;
import com.aetherops.auth.model.User;
import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CalendarOAuthServiceTest {

    @Mock private UserCalendarConnectionRepository connectionRepository;
    @Mock private UserRepository userRepository;
    @Mock private CalendarTokenEncryptor tokenEncryptor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private CalendarOAuthService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.build()).thenReturn(mockRestClient);
        lenient().when(userRepository.findById(userId)).thenReturn(
            Optional.of(User.builder().id(userId).tenantId(tenantId).email("user@acme.com").role(Role.MEMBER).build()));
        service = new CalendarOAuthService(connectionRepository, userRepository, tokenEncryptor, builder, objectMapper,
            "google-client-id", "google-secret", "outlook-client-id", "outlook-secret");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubPostResponse(String jsonResponse) {
        RestClient.RequestBodyUriSpec bodySpec = mock(RestClient.RequestBodyUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(jsonResponse);
        when(mockRestClient.post()).thenReturn(bodySpec);
    }

    @Test
    void connect_newUser_createsConnectionWithEncryptedToken() {
        stubPostResponse("{\"refresh_token\":\"raw-refresh-token\",\"access_token\":\"at\"}");
        when(connectionRepository.findByUserIdAndProvider(userId, CalendarProvider.GOOGLE)).thenReturn(Optional.empty());
        when(tokenEncryptor.encrypt("raw-refresh-token")).thenReturn("encrypted-blob");
        when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserCalendarConnection result = service.connect(userId, tenantId, CalendarProvider.GOOGLE, "auth-code", "https://app/callback");

        assertThat(result.getUserId()).isEqualTo(userId);
        assertThat(result.getTenantId()).isEqualTo(tenantId);
        assertThat(result.getProvider()).isEqualTo(CalendarProvider.GOOGLE);
        assertThat(result.getEncryptedRefreshToken()).isEqualTo("encrypted-blob");
    }

    @Test
    void connect_existingUserProvider_updatesInPlaceRatherThanDuplicating() {
        UserCalendarConnection existing = UserCalendarConnection.builder()
            .id(UUID.randomUUID()).userId(userId).tenantId(tenantId).provider(CalendarProvider.OUTLOOK)
            .encryptedRefreshToken("old-encrypted").build();
        stubPostResponse("{\"refresh_token\":\"new-raw-token\"}");
        when(connectionRepository.findByUserIdAndProvider(userId, CalendarProvider.OUTLOOK)).thenReturn(Optional.of(existing));
        when(tokenEncryptor.encrypt("new-raw-token")).thenReturn("new-encrypted");
        when(connectionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        UserCalendarConnection result = service.connect(userId, tenantId, CalendarProvider.OUTLOOK, "auth-code", "https://app/callback");

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getEncryptedRefreshToken()).isEqualTo("new-encrypted");
        ArgumentCaptor<UserCalendarConnection> captor = ArgumentCaptor.forClass(UserCalendarConnection.class);
        verify(connectionRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
    }

    @Test
    void connect_unknownUser_throwsResourceNotFoundWithoutCallingProvider() {
        UUID unknownUserId = UUID.randomUUID();
        when(userRepository.findById(unknownUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.connect(unknownUserId, tenantId, CalendarProvider.GOOGLE, "auth-code", "https://app/callback"))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(builder);
    }

    @Test
    void connect_userBelongsToDifferentTenant_throwsResourceNotFoundWithoutCallingProvider() {
        UUID otherTenantId = UUID.randomUUID();

        assertThatThrownBy(() -> service.connect(userId, otherTenantId, CalendarProvider.GOOGLE, "auth-code", "https://app/callback"))
            .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(builder);
    }

    @Test
    void connect_noRefreshTokenInResponse_throws() {
        stubPostResponse("{\"access_token\":\"at\"}");

        assertThatThrownBy(() -> service.connect(userId, tenantId, CalendarProvider.GOOGLE, "auth-code", "https://app/callback"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshAccessToken_returnsAccessTokenFromResponse() {
        UserCalendarConnection connection = UserCalendarConnection.builder()
            .userId(userId).tenantId(tenantId).provider(CalendarProvider.GOOGLE)
            .encryptedRefreshToken("encrypted-blob").build();
        when(tokenEncryptor.decrypt("encrypted-blob")).thenReturn("raw-refresh-token");
        stubPostResponse("{\"access_token\":\"fresh-access-token\"}");

        String accessToken = service.refreshAccessToken(connection);

        assertThat(accessToken).isEqualTo("fresh-access-token");
    }

    @Test
    void refreshAccessToken_noAccessTokenInResponse_throws() {
        UserCalendarConnection connection = UserCalendarConnection.builder()
            .userId(userId).tenantId(tenantId).provider(CalendarProvider.OUTLOOK)
            .encryptedRefreshToken("encrypted-blob").build();
        when(tokenEncryptor.decrypt("encrypted-blob")).thenReturn("raw-refresh-token");
        stubPostResponse("{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> service.refreshAccessToken(connection))
            .isInstanceOf(IllegalStateException.class);
    }
}
