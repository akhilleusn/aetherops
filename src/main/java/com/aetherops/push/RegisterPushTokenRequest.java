package com.aetherops.push;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Phase 1 Step E, item 1 (finding 7): userId and tenantId are NOT client input anymore —
 * both are derived server-side from the authenticated principal
 * (CurrentUserContext.get() / TenantContext.get()) in PushTokenController. Removed from
 * this request entirely rather than accepted-and-ignored, so there's no field here a
 * caller could be misled into thinking selects who the token is registered for.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPushTokenRequest {

    @NotBlank(message = "expoPushToken is required")
    private String expoPushToken;

    private String platform;
}
