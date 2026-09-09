package com.aetherops.push;

import com.aetherops.auth.security.CurrentUserContext;
import com.aetherops.common.response.ApiResponse;
import com.aetherops.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 1 Step E, item 1 (finding 7): this used to be /api/v1/users/{userId}/push-token,
 * with tenantId taken from the request body — both pure client input, no check that either
 * matched the caller. Any tenant member could silently overwrite another member's push
 * token, which in an incident tool means silently un-paging someone.
 *
 * Fixed by deriving both identifiers from the authenticated principal instead of accepting
 * them as input at all — see RegisterPushTokenRequest's javadoc. The {userId} path segment
 * is gone rather than kept-and-ignored: once it can never affect which row is written, a
 * URL that still displays it would misrepresent the API as letting the caller target a
 * user, which is worse than not having the segment. "/users/me/..." keeps the resource
 * under the same /users/ namespace as CalendarConnectionController's self-scoped
 * "/users/{userId}/..." routes, while making the self-only-ness part of the URL itself
 * instead of something a reader has to go verify in the handler.
 */
@RestController
@RequestMapping("/api/v1/users/me/push-token")
@RequiredArgsConstructor
@Tag(name = "Push Notifications", description = "Mobile device push token registration")
public class PushTokenController {

    private final PushNotificationService pushNotificationService;

    @PostMapping
    @Operation(summary = "Register (or refresh) this device's Expo push token for the current authenticated user")
    public ApiResponse<PushDeviceToken> register(@Valid @RequestBody RegisterPushTokenRequest request) {
        return ApiResponse.success(pushNotificationService.registerToken(
            CurrentUserContext.get(), TenantContext.get(), request.getExpoPushToken(), request.getPlatform()));
    }
}
