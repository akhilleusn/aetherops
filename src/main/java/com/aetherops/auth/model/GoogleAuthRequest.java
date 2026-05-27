package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record GoogleAuthRequest(
    @NotBlank String idToken,
    UUID tenantId
) {}
