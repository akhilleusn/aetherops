package com.aetherops.calendar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PtoAwarenessServiceTest {

    @Mock private UserCalendarConnectionRepository connectionRepository;
    @Mock private CalendarOAuthService oAuthService;
    @Mock private CalendarBusyStatusClient googleClient;
    @Mock private CalendarBusyStatusClient outlookClient;

    private PtoAwarenessService service;

    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.now();

    private UserCalendarConnection connection(CalendarProvider provider) {
        return UserCalendarConnection.builder()
            .userId(userId).provider(provider).encryptedRefreshToken("enc").calendarId("primary").build();
    }

    @Test
    void isUserAvailable_noConnections_returnsTrue() {
        lenient().when(googleClient.provider()).thenReturn(CalendarProvider.GOOGLE);
        service = new PtoAwarenessService(connectionRepository, oAuthService, List.of(googleClient));
        when(connectionRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.isUserAvailable(userId, now)).isTrue();
        verifyNoInteractions(oAuthService);
    }

    @Test
    void isUserAvailable_busyClientReportsOoo_returnsFalse() {
        when(googleClient.provider()).thenReturn(CalendarProvider.GOOGLE);
        service = new PtoAwarenessService(connectionRepository, oAuthService, List.of(googleClient));
        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(connection(CalendarProvider.GOOGLE)));
        when(oAuthService.refreshAccessToken(any())).thenReturn("access-token");
        when(googleClient.hasAllDayOrOofEventAt("access-token", "primary", now)).thenReturn(true);

        assertThat(service.isUserAvailable(userId, now)).isFalse();
    }

    @Test
    void isUserAvailable_busyClientReportsNoEvent_returnsTrue() {
        when(googleClient.provider()).thenReturn(CalendarProvider.GOOGLE);
        service = new PtoAwarenessService(connectionRepository, oAuthService, List.of(googleClient));
        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(connection(CalendarProvider.GOOGLE)));
        when(oAuthService.refreshAccessToken(any())).thenReturn("access-token");
        when(googleClient.hasAllDayOrOofEventAt("access-token", "primary", now)).thenReturn(false);

        assertThat(service.isUserAvailable(userId, now)).isTrue();
    }

    @Test
    void isUserAvailable_oAuthRefreshThrows_failsOpenReturnsTrue() {
        when(googleClient.provider()).thenReturn(CalendarProvider.GOOGLE);
        service = new PtoAwarenessService(connectionRepository, oAuthService, List.of(googleClient));
        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(connection(CalendarProvider.GOOGLE)));
        when(oAuthService.refreshAccessToken(any())).thenThrow(new RuntimeException("token expired"));

        assertThat(service.isUserAvailable(userId, now)).isTrue();
    }

    @Test
    void isUserAvailable_multipleConnections_anyOooMakesUnavailable() {
        when(googleClient.provider()).thenReturn(CalendarProvider.GOOGLE);
        when(outlookClient.provider()).thenReturn(CalendarProvider.OUTLOOK);
        service = new PtoAwarenessService(connectionRepository, oAuthService, List.of(googleClient, outlookClient));
        when(connectionRepository.findByUserId(userId))
            .thenReturn(List.of(connection(CalendarProvider.GOOGLE), connection(CalendarProvider.OUTLOOK)));
        when(oAuthService.refreshAccessToken(any())).thenReturn("access-token");
        when(googleClient.hasAllDayOrOofEventAt(any(), any(), any())).thenReturn(false);
        when(outlookClient.hasAllDayOrOofEventAt(any(), any(), any())).thenReturn(true);

        assertThat(service.isUserAvailable(userId, now)).isFalse();
    }

    @Test
    void isUserAvailable_noMatchingBusyStatusClientForProvider_treatedAsAvailable() {
        when(googleClient.provider()).thenReturn(CalendarProvider.GOOGLE);
        service = new PtoAwarenessService(connectionRepository, oAuthService, List.of(googleClient));
        when(connectionRepository.findByUserId(userId)).thenReturn(List.of(connection(CalendarProvider.OUTLOOK)));

        assertThat(service.isUserAvailable(userId, now)).isTrue();
        verifyNoInteractions(oAuthService);
    }
}
