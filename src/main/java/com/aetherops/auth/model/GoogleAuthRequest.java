package com.aetherops.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;

import java.util.UUID;

/**
 * Same rule as {@link RegisterRequest}: a brand-new Google-linked user joins a tenant only
 * via a server-issued {@code invitationToken}, never a client-supplied tenantId.
 */
public record GoogleAuthRequest(
    @NotBlank String idToken,
    String invitationToken,
    @Null(message = "tenantId must not be supplied; joining a tenant requires an invitationToken")
    UUID tenantId
) {
    public GoogleAuthRequest(String idToken, String invitationToken) {
        this(idToken, invitationToken, null);
    }

    @Override
    public String toString() {
        return "GoogleAuthRequest[idToken=[REDACTED], invitationToken=[REDACTED], tenantId=" + tenantId + "]";
    }
}
