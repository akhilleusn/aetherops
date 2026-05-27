package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;

public record TwoFaVerifyRequest(
    @NotBlank String partialToken,
    @NotBlank String code
) {}
