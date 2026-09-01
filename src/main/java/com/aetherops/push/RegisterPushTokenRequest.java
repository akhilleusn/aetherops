package com.aetherops.push;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterPushTokenRequest {

    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    @NotBlank(message = "expoPushToken is required")
    private String expoPushToken;

    private String platform;
}
