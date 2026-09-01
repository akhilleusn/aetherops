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
 * Reads Microsoft Graph's /me/calendarview directly via RestClient (no MSAL/Graph
 * SDK dependency). Graph has a first-class "showAs: oof" status, which is a more
 * reliable OOO signal than Google's title-keyword heuristic, but we still fall
 * back to a subject-text check for tenants that mark PTO with a plain all-day
 * event instead of setting showAs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutlookCalendarBusyStatusClient implements CalendarBusyStatusClient {

    private static final String[] OOO_KEYWORDS = {"ooo", "out of office", "pto"};

    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public CalendarProvider provider() {
        return CalendarProvider.OUTLOOK;
    }

    @Override
    public boolean hasAllDayOrOofEventAt(String accessToken, String calendarId, Instant at) {
        Instant startDateTime = at.minus(1, ChronoUnit.DAYS);
        Instant endDateTime = at.plus(1, ChronoUnit.DAYS);

        String uri = "https://graph.microsoft.com/v1.0/me/calendarview?startDateTime="
            + URLEncoder.encode(startDateTime.toString(), StandardCharsets.UTF_8)
            + "&endDateTime=" + URLEncoder.encode(endDateTime.toString(), StandardCharsets.UTF_8);

        try {
            String body = restClientBuilder.build().get()
                .uri(uri)
                .headers(h -> h.setBearerAuth(accessToken))
                .retrieve()
                .body(String.class);

            JsonNode values = objectMapper.readTree(body).path("value");
            for (JsonNode event : values) {
                boolean allDay = event.path("isAllDay").asBoolean(false);
                boolean showAsOof = "oof".equalsIgnoreCase(event.path("showAs").asText(""));
                String subject = event.path("subject").asText("").toLowerCase(Locale.ROOT);
                boolean oooTitled = containsOooKeyword(subject);
                if (allDay || showAsOof || oooTitled) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Microsoft Graph calendarview failed", e);
            return false;
        }
    }

    private boolean containsOooKeyword(String subject) {
        for (String keyword : OOO_KEYWORDS) {
            if (subject.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
