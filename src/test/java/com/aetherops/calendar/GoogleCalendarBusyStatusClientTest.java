package com.aetherops.calendar;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GoogleCalendarBusyStatusClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private GoogleCalendarBusyStatusClient client;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.build()).thenReturn(mockRestClient);
        client = new GoogleCalendarBusyStatusClient(builder, objectMapper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubGetResponse(String jsonResponse) {
        RestClient.RequestHeadersUriSpec headersSpec = mock(RestClient.RequestHeadersUriSpec.class, Answers.RETURNS_SELF);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(jsonResponse);
        when(mockRestClient.get()).thenReturn(headersSpec);
    }

    @Test
    void hasAllDayOrOofEventAt_allDayEvent_returnsTrue() {
        stubGetResponse("{\"items\":[{\"start\":{\"date\":\"2026-06-01\"},\"summary\":\"Team offsite\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", "primary", Instant.now())).isTrue();
    }

    @Test
    void hasAllDayOrOofEventAt_oooTitledTimedEvent_returnsTrue() {
        stubGetResponse("{\"items\":[{\"start\":{\"dateTime\":\"2026-06-01T09:00:00Z\"},\"summary\":\"OOO - back Monday\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", "primary", Instant.now())).isTrue();
    }

    @Test
    void hasAllDayOrOofEventAt_normalMeeting_returnsFalse() {
        stubGetResponse("{\"items\":[{\"start\":{\"dateTime\":\"2026-06-01T09:00:00Z\"},\"summary\":\"Sprint planning\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", "primary", Instant.now())).isFalse();
    }

    @Test
    void hasAllDayOrOofEventAt_noEvents_returnsFalse() {
        stubGetResponse("{\"items\":[]}");
        assertThat(client.hasAllDayOrOofEventAt("token", "primary", Instant.now())).isFalse();
    }

    @Test
    void hasAllDayOrOofEventAt_apiThrows_returnsFalseNotThrows() {
        when(mockRestClient.get()).thenThrow(new RuntimeException("network error"));
        assertThat(client.hasAllDayOrOofEventAt("token", "primary", Instant.now())).isFalse();
    }

    @Test
    void provider_isGoogle() {
        assertThat(client.provider()).isEqualTo(CalendarProvider.GOOGLE);
    }
}
