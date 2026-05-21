package com.aetherops.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
    @NotNull UUID tenantId
) {}
