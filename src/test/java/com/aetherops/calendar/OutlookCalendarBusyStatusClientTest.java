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
class OutlookCalendarBusyStatusClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestClient mockRestClient;
    private RestClient.Builder builder;
    private OutlookCalendarBusyStatusClient client;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(RestClient.class);
        builder = mock(RestClient.Builder.class);
        lenient().when(builder.build()).thenReturn(mockRestClient);
        client = new OutlookCalendarBusyStatusClient(builder, objectMapper);
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
    void hasAllDayOrOofEventAt_isAllDayTrue_returnsTrue() {
        stubGetResponse("{\"value\":[{\"isAllDay\":true,\"showAs\":\"free\",\"subject\":\"Company holiday\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", null, Instant.now())).isTrue();
    }

    @Test
    void hasAllDayOrOofEventAt_showAsOof_returnsTrue() {
        stubGetResponse("{\"value\":[{\"isAllDay\":false,\"showAs\":\"oof\",\"subject\":\"Vacation\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", null, Instant.now())).isTrue();
    }

    @Test
    void hasAllDayOrOofEventAt_subjectKeyword_returnsTrue() {
        stubGetResponse("{\"value\":[{\"isAllDay\":false,\"showAs\":\"busy\",\"subject\":\"PTO day\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", null, Instant.now())).isTrue();
    }

    @Test
    void hasAllDayOrOofEventAt_normalMeeting_returnsFalse() {
        stubGetResponse("{\"value\":[{\"isAllDay\":false,\"showAs\":\"busy\",\"subject\":\"1:1 sync\"}]}");
        assertThat(client.hasAllDayOrOofEventAt("token", null, Instant.now())).isFalse();
    }

    @Test
    void hasAllDayOrOofEventAt_apiThrows_returnsFalseNotThrows() {
        when(mockRestClient.get()).thenThrow(new RuntimeException("network error"));
        assertThat(client.hasAllDayOrOofEventAt("token", null, Instant.now())).isFalse();
    }

    @Test
    void provider_isOutlook() {
        assertThat(client.provider()).isEqualTo(CalendarProvider.OUTLOOK);
    }
}
