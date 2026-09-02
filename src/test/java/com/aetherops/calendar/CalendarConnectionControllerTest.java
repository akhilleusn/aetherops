package com.aetherops.calendar;

import com.aetherops.auth.security.CurrentUserContext;
import com.aetherops.common.exception.ForbiddenException;
import com.aetherops.common.exception.InvalidOAuthStateException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarConnectionControllerTest {

    @Mock private CalendarOAuthService oAuthService;
    @Mock private CalendarOAuthStateService stateService;
    @Mock private UserCalendarConnectionRepository connectionRepository;

    @InjectMocks
    private CalendarConnectionController controller;

    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    /** Simulates CurrentUserContextFilter having authenticated this request as {@link #userId}. */
    @BeforeEach
    void authenticateAsUserId() {
        CurrentUserContext.set(userId);
        TenantContext.set(tenantId);
    }

    @AfterEach
    void cleanup() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void connect_callerIsNotThePathUser_throwsForbiddenWithoutCallingService() {
        CurrentUserContext.set(UUID.randomUUID()); // some other authenticated user
        ConnectCalendarRequest request = new ConnectCalendarRequest(tenantId, CalendarProvider.GOOGLE, "code", "https://app/cb", "valid-state");

        assertThatThrownBy(() -> controller.connect(userId, request))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(oAuthService);
    }

    @Test
    void connect_noAuthenticatedCaller_throwsForbidden() {
        CurrentUserContext.clear();
        ConnectCalendarRequest request = new ConnectCalendarRequest(tenantId, CalendarProvider.GOOGLE, "code", "https://app/cb", "valid-state");

        assertThatThrownBy(() -> controller.connect(userId, request))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(oAuthService);
    }

    @Test
    void list_callerIsNotThePathUser_throwsForbiddenWithoutQuerying() {
        CurrentUserContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> controller.list(userId))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(connectionRepository);
    }

    @Test
    void disconnect_callerIsNotThePathUser_throwsForbiddenWithoutDeleting() {
        CurrentUserContext.set(UUID.randomUUID());
        UUID connectionId = UUID.randomUUID();

        assertThatThrownBy(() -> controller.disconnect(userId, connectionId))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(connectionRepository);
    }

    @Test
    void authorizeUrl_callerIsNotThePathUser_throwsForbiddenWithoutIssuingState() {
        CurrentUserContext.set(UUID.randomUUID());

        assertThatThrownBy(() -> controller.authorizeUrl(userId, tenantId, CalendarProvider.GOOGLE, "https://app/cb"))
            .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(stateService, oAuthService);
    }

    @Test
    void authorizeUrl_delegatesToStateServiceAndBuildsUrl() {
        when(stateService.issue(userId, tenantId, CalendarProvider.GOOGLE)).thenReturn("issued-state");
        when(oAuthService.buildAuthorizeUrl(CalendarProvider.GOOGLE, "https://app/cb", "issued-state"))
            .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=issued-state");

        ApiResponse<java.util.Map<String, String>> response =
            controller.authorizeUrl(userId, tenantId, CalendarProvider.GOOGLE, "https://app/cb");

        assertThat(response.getData().get("url")).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth?state=issued-state");
    }

    /**
     * The core proof that this connect flow isn't vulnerable to OAuth authorization-code
     * injection / CSRF: a request whose state doesn't check out (forged, replayed for a
     * different user, expired — anything CalendarOAuthStateService rejects) must be turned
     * away with a 400-mapped exception *before* the authorization code is ever sent to the
     * provider for exchange.
     */
    @Test
    void connect_invalidState_rejectedWithoutExchangingCodeWithProvider() {
        ConnectCalendarRequest request = new ConnectCalendarRequest(
            tenantId, CalendarProvider.GOOGLE, "captured-code", "https://app/cb", "forged-or-replayed-state");
        doThrow(new InvalidOAuthStateException("Calendar OAuth state does not match this request"))
            .when(stateService).verify("forged-or-replayed-state", userId, tenantId, CalendarProvider.GOOGLE);

        assertThatThrownBy(() -> controller.connect(userId, request))
            .isInstanceOf(InvalidOAuthStateException.class);
        verifyNoInteractions(oAuthService);
    }

    @Test
    void connect_delegatesToServiceAndReturnsSafeResponseWithoutToken() {
        ConnectCalendarRequest request = new ConnectCalendarRequest(tenantId, CalendarProvider.GOOGLE, "code", "https://app/cb", "valid-state");
        UserCalendarConnection saved = UserCalendarConnection.builder()
            .id(UUID.randomUUID()).userId(userId).provider(CalendarProvider.GOOGLE)
            .encryptedRefreshToken("super-secret-encrypted-token").connectedAt(Instant.now()).build();
        when(oAuthService.connect(userId, tenantId, CalendarProvider.GOOGLE, "code", "https://app/cb")).thenReturn(saved);

        ApiResponse<CalendarConnectionResponse> response = controller.connect(userId, request);

        assertThat(response.getData().getProvider()).isEqualTo(CalendarProvider.GOOGLE);
        assertThat(response.getData().toString()).doesNotContain("super-secret-encrypted-token");
    }

    @Test
    void list_returnsAllConnectionsForUser() {
        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(
            UserCalendarConnection.builder().id(UUID.randomUUID()).userId(userId).provider(CalendarProvider.GOOGLE).build(),
            UserCalendarConnection.builder().id(UUID.randomUUID()).userId(userId).provider(CalendarProvider.OUTLOOK).build()));

        ApiResponse<List<CalendarConnectionResponse>> response = controller.list(userId);

        assertThat(response.getData()).hasSize(2);
    }

    @Test
    void disconnect_ownedConnection_deletesIt() {
        UUID connectionId = UUID.randomUUID();
        UserCalendarConnection connection = UserCalendarConnection.builder().id(connectionId).userId(userId).build();
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));

        controller.disconnect(userId, connectionId);

        verify(connectionRepository).delete(connection);
    }

    @Test
    void disconnect_connectionBelongsToDifferentUser_throwsResourceNotFoundWithoutDeleting() {
        UUID connectionId = UUID.randomUUID();
        UserCalendarConnection connection = UserCalendarConnection.builder().id(connectionId).userId(UUID.randomUUID()).build();
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> controller.disconnect(userId, connectionId))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(connectionRepository, org.mockito.Mockito.never()).delete(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disconnect_unknownConnection_throwsResourceNotFound() {
        UUID connectionId = UUID.randomUUID();
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.disconnect(userId, connectionId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
