package com.aetherops.calendar;

import com.aetherops.auth.security.CurrentUserContext;
import com.aetherops.common.exception.ForbiddenException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{userId}/calendar-connections")
@RequiredArgsConstructor
@Tag(name = "Calendar Integration", description = "Google/Outlook calendar connections for on-call PTO awareness")
public class CalendarConnectionController {

    private final CalendarOAuthService oAuthService;
    private final UserCalendarConnectionRepository connectionRepository;

    @PostMapping
    @Operation(summary = "Connect a Google or Outlook calendar via an OAuth authorization code")
    public ApiResponse<CalendarConnectionResponse> connect(
        @PathVariable UUID userId,
        @Valid @RequestBody ConnectCalendarRequest request
    ) {
        requireSelf(userId);
        UserCalendarConnection connection = oAuthService.connect(
            userId, request.getTenantId(), request.getProvider(), request.getAuthorizationCode(), request.getRedirectUri());
        return ApiResponse.success(CalendarConnectionResponse.from(connection));
    }

    @GetMapping
    @Operation(summary = "List this user's connected calendars")
    public ApiResponse<List<CalendarConnectionResponse>> list(@PathVariable UUID userId) {
        requireSelf(userId);
        List<CalendarConnectionResponse> connections = connectionRepository.findByUserId(userId).stream()
            .map(CalendarConnectionResponse::from)
            .toList();
        return ApiResponse.success(connections);
    }

    @DeleteMapping("/{connectionId}")
    @Operation(summary = "Disconnect a calendar")
    public ApiResponse<Void> disconnect(@PathVariable UUID userId, @PathVariable UUID connectionId) {
        requireSelf(userId);
        UserCalendarConnection connection = connectionRepository.findById(connectionId)
            .filter(c -> c.getUserId().equals(userId))
            .orElseThrow(() -> new ResourceNotFoundException("Calendar connection not found: " + connectionId));
        connectionRepository.delete(connection);
        return ApiResponse.success(null);
    }

    /**
     * Every endpoint here is scoped to {@code {userId}} in the path, but nothing upstream
     * (JwtAuthFilter, TenantContextFilter) ties that path segment to the caller's own
     * identity. Without this check, any authenticated user could read, hijack (by connecting
     * their own OAuth code over someone else's row), or delete another user's calendar
     * connection just by knowing/guessing their userId.
     */
    private void requireSelf(UUID userId) {
        UUID callerId = CurrentUserContext.get();
        if (callerId == null || !callerId.equals(userId)) {
            throw new ForbiddenException("Cannot access another user's calendar connections");
        }
    }
}
