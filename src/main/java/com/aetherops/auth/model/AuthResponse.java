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
    private String refreshToken;
    private UUID userId;
    private UUID tenantId;
    private String email;
    private String role;
    private Boolean requiresTwoFactor;
    private String partialToken;

    @Override
    public String toString() {
        return "AuthResponse(token=[REDACTED], expiresAt=" + expiresAt + ", refreshToken=[REDACTED], "
            + "userId=" + userId + ", tenantId=" + tenantId + ", email=" + email + ", role=" + role
            + ", requiresTwoFactor=" + requiresTwoFactor + ", partialToken=[REDACTED])";
    }
}
