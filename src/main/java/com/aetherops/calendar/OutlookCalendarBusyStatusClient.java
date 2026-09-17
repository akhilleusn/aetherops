package com.aetherops.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
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
 *
 * <p>Step H2 (see STEP_REPORT.md): this call sits directly in the paging path --
 * {@code OnCallService.pageOnCall()} calls it (via {@code PtoAwarenessService}) before Twilio,
 * for every rotation participant with an Outlook connection. {@code PtoAwarenessService}
 * already catches every exception per-connection and fails open ("assume available"), so a
 * circuit breaker here is safe by construction: once Microsoft Graph is clearly down, tripping
 * the breaker just makes each check fail (and therefore fail open) faster, without changing
 * that fail-open behavior at all. Deliberately no {@code @Retry} -- this is a read, so retrying
 * is safe, but paging should not wait through a retry's backoff before falling open; a single
 * bounded attempt is enough.
 *
 * <p>This method used to catch its own exceptions and return {@code false} directly -- removed,
 * because a circuit breaker that never sees a thrown exception can never trip (this method would
 * always look "successful" to it). {@code PtoAwarenessService}'s own catch already produces the
 * identical fail-open outcome one level up, so nothing about the end-to-end behavior changes --
 * only which layer logs and catches it.
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
    @CircuitBreaker(name = "calendar-outlook")
    public boolean hasAllDayOrOofEventAt(String accessToken, String calendarId, Instant at) {
        Instant startDateTime = at.minus(1, ChronoUnit.DAYS);
        Instant endDateTime = at.plus(1, ChronoUnit.DAYS);

        String uri = "https://graph.microsoft.com/v1.0/me/calendarview?startDateTime="
            + URLEncoder.encode(startDateTime.toString(), StandardCharsets.UTF_8)
            + "&endDateTime=" + URLEncoder.encode(endDateTime.toString(), StandardCharsets.UTF_8);

        String body = restClientBuilder.build().get()
            .uri(uri)
            .headers(h -> h.setBearerAuth(accessToken))
            .retrieve()
            .body(String.class);

        JsonNode values;
        try {
            values = objectMapper.readTree(body).path("value");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Microsoft Graph calendarview response", e);
        }
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
