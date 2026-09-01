package com.aetherops.calendar;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one method the rest of the app should call: "is this user available right
 * now, or are they on PTO?". Fail-open throughout — a paging system must never go
 * silent because a calendar connection is missing or a calendar API call failed.
 */
@Service
@Slf4j
public class PtoAwarenessService {

    private final UserCalendarConnectionRepository connectionRepository;
    private final CalendarOAuthService oAuthService;
    private final Map<CalendarProvider, CalendarBusyStatusClient> busyStatusClients;

    public PtoAwarenessService(UserCalendarConnectionRepository connectionRepository,
                                CalendarOAuthService oAuthService,
                                List<CalendarBusyStatusClient> clients) {
        this.connectionRepository = connectionRepository;
        this.oAuthService = oAuthService;
        this.busyStatusClients = clients.stream()
            .collect(Collectors.toMap(CalendarBusyStatusClient::provider, Function.identity()));
    }

    public boolean isUserAvailable(UUID userId, Instant at) {
        List<UserCalendarConnection> connections = connectionRepository.findByUserId(userId);
        if (connections.isEmpty()) {
            return true;
        }

        for (UserCalendarConnection connection : connections) {
            try {
                CalendarBusyStatusClient client = busyStatusClients.get(connection.getProvider());
                if (client == null) {
                    continue;
                }
                String accessToken = oAuthService.refreshAccessToken(connection);
                if (client.hasAllDayOrOofEventAt(accessToken, connection.getCalendarId(), at)) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("PTO check failed for userId={} provider={}, assuming available",
                    userId, connection.getProvider(), e);
            }
        }
        return true;
    }
}
