package com.aetherops.push;

import com.aetherops.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/{userId}/push-token")
@RequiredArgsConstructor
@Tag(name = "Push Notifications", description = "Mobile device push token registration")
public class PushTokenController {

    private final PushNotificationService pushNotificationService;

    @PostMapping
    @Operation(summary = "Register (or refresh) this device's Expo push token for the current user")
    public ApiResponse<PushDeviceToken> register(
        @PathVariable UUID userId,
        @Valid @RequestBody RegisterPushTokenRequest request
    ) {
        return ApiResponse.success(pushNotificationService.registerToken(
            userId, request.getTenantId(), request.getExpoPushToken(), request.getPlatform()));
    }
}
