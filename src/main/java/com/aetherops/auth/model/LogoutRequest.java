package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
    @NotBlank String refreshToken
) {
    @Override
    public String toString() {
        return "LogoutRequest[refreshToken=[REDACTED]]";
    }
}
