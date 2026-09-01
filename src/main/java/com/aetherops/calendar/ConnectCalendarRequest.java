package com.aetherops.calendar;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectCalendarRequest {

    @NotNull(message = "tenantId is required")
    private UUID tenantId;

    @NotNull(message = "provider is required")
    private CalendarProvider provider;

    @NotBlank(message = "authorizationCode is required")
    private String authorizationCode;

    @NotBlank(message = "redirectUri is required")
    private String redirectUri;
}
