package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank String token,
    @NotBlank @Size(min = 8) String newPassword
) {
    @Override
    public String toString() {
        return "ResetPasswordRequest[token=[REDACTED], newPassword=[REDACTED]]";
    }
}
