package com.aetherops.auth.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AuthResponse {
    private String token;
    private Instant expiresAt;
    private UUID userId;
    private UUID tenantId;
    private String email;
    private String role;
    private Boolean requiresTwoFactor;
    private String partialToken;
}
