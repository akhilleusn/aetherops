package com.aetherops.auth.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The caller never chooses which tenant they join. An {@code invitationToken} (issued
 * server-side) binds registration to an existing tenant -- registration is by invitation only,
 * so this is required, not optional. A brand-new tenant is created through the authenticated
 * {@code POST /api/v1/tenants} endpoint instead, not by self-registering. {@code tenantId}/
 * {@code tenantSlug} exist on this DTO only so a client-supplied value is rejected outright
 * rather than silently ignored.
 */
public record RegisterRequest(
    @NotBlank @Email String email,
    @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
    @NotBlank(message = "invitationToken is required -- registration is by invitation only")
    String invitationToken,
    @Null(message = "tenantId must not be supplied; registration joins a tenant via invitationToken or creates a new one")
    UUID tenantId,
    @Null(message = "tenantSlug must not be supplied; registration joins a tenant via invitationToken or creates a new one")
    String tenantSlug
) {
    public RegisterRequest(String email, String password, String invitationToken) {
        this(email, password, invitationToken, null, null);
    }

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=[REDACTED], invitationToken=[REDACTED], "
            + "tenantId=" + tenantId + ", tenantSlug=" + tenantSlug + "]";
    }
}
