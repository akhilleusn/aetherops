package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;

public record TwoFaVerifySetupRequest(
    @NotBlank String code
) {
    @Override
    public String toString() {
        return "TwoFaVerifySetupRequest[code=[REDACTED]]";
    }
}
