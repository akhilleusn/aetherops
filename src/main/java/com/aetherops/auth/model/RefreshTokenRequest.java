package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
    @NotBlank String refreshToken
) {
    @Override
    public String toString() {
        return "RefreshTokenRequest[refreshToken=[REDACTED]]";
    }
}
