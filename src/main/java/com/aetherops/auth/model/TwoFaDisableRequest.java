package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;

public record TwoFaDisableRequest(
    @NotBlank String password
) {
    @Override
    public String toString() {
        return "TwoFaDisableRequest[password=[REDACTED]]";
    }
}
