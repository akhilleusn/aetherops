package com.aetherops.calendar;

import java.time.Instant;

/** One implementation per {@link CalendarProvider}. */
public interface CalendarBusyStatusClient {

    CalendarProvider provider();

    /**
     * Best-effort check for an all-day or explicitly out-of-office event covering
     * {@code at}. Implementations must swallow HTTP/network failures and return
     * {@code false} — a calendar-API outage must never be mistaken for "on PTO".
     */
    boolean hasAllDayOrOofEventAt(String accessToken, String calendarId, Instant at);
}
