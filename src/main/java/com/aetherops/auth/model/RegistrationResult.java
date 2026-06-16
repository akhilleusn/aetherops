package com.aetherops.auth.model;

import java.util.UUID;

public record RegistrationResult(UUID userId, String message) {}
