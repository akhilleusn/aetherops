package com.aetherops.calendar;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** Never includes the encrypted refresh token — this DTO is the safe outward-facing shape. */
@Data
@Builder
public class CalendarConnectionResponse {
    private UUID id;
    private UUID userId;
    private CalendarProvider provider;
    private Instant connectedAt;
    private Instant lastSyncedAt;

    public static CalendarConnectionResponse from(UserCalendarConnection connection) {
        return CalendarConnectionResponse.builder()
            .id(connection.getId())
            .userId(connection.getUserId())
            .provider(connection.getProvider())
            .connectedAt(connection.getConnectedAt())
            .lastSyncedAt(connection.getLastSyncedAt())
            .build();
    }
}
