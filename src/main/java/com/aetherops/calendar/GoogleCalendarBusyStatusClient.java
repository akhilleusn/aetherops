package com.aetherops.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Reads the Google Calendar v3 events.list endpoint directly via RestClient (no
 * google-api-client SDK dependency). "All-day or OOO" is inherently heuristic —
 * Google Calendar has no first-class PTO concept, so this looks for an all-day
 * event (a date-only start, no time-of-day) or a title containing an OOO/PTO
 * keyword.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GoogleCalendarBusyStatusClient implements CalendarBusyStatusClient {

    private static final String[] OOO_KEYWORDS = {"ooo", "out of office", "pto"};

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.GOOGLE;
    }

    @Override
    public boolean hasAllDayOrOofEventAt(String accessToken, String calendarId, Instant at) {
        String cal = calendarId != null && !calendarId.isBlank() ? calendarId : "primary";
        Instant timeMin = at.minus(1, ChronoUnit.DAYS);
        Instant timeMax = at.plus(1, ChronoUnit.DAYS);

        String uri = "https://www.googleapis.com/calendar/v3/calendars/"
            + URLEncoder.encode(cal, StandardCharsets.UTF_8)
            + "/events?singleEvents=true&timeMin=" + URLEncoder.encode(timeMin.toString(), StandardCharsets.UTF_8)
            + "&timeMax=" + URLEncoder.encode(timeMax.toString(), StandardCharsets.UTF_8);

        try {
            String body = restClientBuilder.build().get()
                .uri(uri)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(String.class);

            JsonNode items = objectMapper.readTree(body).path("items");
            for (JsonNode item : items) {
                boolean allDay = item.path("start").has("date") && !item.path("start").has("dateTime");
                String summary = item.path("summary").asText("").toLowerCase(Locale.ROOT);
                boolean oooTitled = containsOooKeyword(summary);
                if (allDay || oooTitled) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Google Calendar events.list failed calendarId={}", cal, e);
            return false;
        }
    }

    private boolean containsOooKeyword(String summary) {
        for (String keyword : OOO_KEYWORDS) {
            if (summary.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
