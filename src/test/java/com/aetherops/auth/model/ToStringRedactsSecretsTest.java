package com.aetherops.auth.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 finding 6 (see STEP_REPORT.md): every DTO carrying a password, token, or secret must
 * redact it from toString() — nothing logs these DTOs today, but a record/@Data class's default
 * toString() would print the raw value verbatim if one ever were logged (a request-logging
 * filter, an exception handler that includes the request body, a stray debug log statement).
 */
class ToStringRedactsSecretsTest {

    private static final String SECRET_VALUE = "super-secret-value-must-not-appear";

    @Test
    void loginRequest_redactsPassword() {
        String s = new LoginRequest("user@example.com", SECRET_VALUE).toString();
        assertThat(s).contains("user@example.com").doesNotContain(SECRET_VALUE);
    }

    @Test
    void registerRequest_redactsPasswordAndInvitationToken() {
        String s = new RegisterRequest("user@example.com", SECRET_VALUE, SECRET_VALUE).toString();
        assertThat(s).contains("user@example.com").doesNotContain(SECRET_VALUE);
    }

    @Test
    void resetPasswordRequest_redactsTokenAndNewPassword() {
        String s = new ResetPasswordRequest(SECRET_VALUE, SECRET_VALUE).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void forgotPasswordRequest_carriesNoSecret_emailStillVisible() {
        // No password/token/key field — nothing to redact, included here to document that
        // this DTO was checked, not skipped.
        String s = new ForgotPasswordRequest("user@example.com").toString();
        assertThat(s).contains("user@example.com");
    }

    @Test
    void googleAuthRequest_redactsIdTokenAndInvitationToken() {
        String s = new GoogleAuthRequest(SECRET_VALUE, SECRET_VALUE, UUID.randomUUID()).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void refreshTokenRequest_redactsRefreshToken() {
        String s = new RefreshTokenRequest(SECRET_VALUE).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void logoutRequest_redactsRefreshToken() {
        String s = new LogoutRequest(SECRET_VALUE).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void twoFaDisableRequest_redactsPassword() {
        String s = new TwoFaDisableRequest(SECRET_VALUE).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void twoFaVerifyRequest_redactsPartialTokenAndCode() {
        String s = new TwoFaVerifyRequest(SECRET_VALUE, SECRET_VALUE).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void twoFaVerifySetupRequest_redactsCode() {
        String s = new TwoFaVerifySetupRequest(SECRET_VALUE).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void twoFaSetupResponse_redactsSecretAndQrCodeUrlAndBackupCodes() {
        // qrCodeUrl embeds the raw TOTP secret as a query param (otpauth://...?secret=...).
        String qrCodeUrlWithEmbeddedSecret = "otpauth://totp/AetherOps:user@example.com?secret=" + SECRET_VALUE;
        String s = new TwoFaSetupResponse(qrCodeUrlWithEmbeddedSecret, SECRET_VALUE, List.of(SECRET_VALUE)).toString();
        assertThat(s).doesNotContain(SECRET_VALUE);
    }

    @Test
    void authResponse_redactsTokenRefreshTokenAndPartialToken() {
        AuthResponse response = AuthResponse.builder()
            .token(SECRET_VALUE)
            .refreshToken(SECRET_VALUE)
            .partialToken(SECRET_VALUE)
            .expiresAt(Instant.now())
            .userId(UUID.randomUUID())
            .tenantId(UUID.randomUUID())
            .email("user@example.com")
            .role("MEMBER")
            .requiresTwoFactor(false)
            .build();

        String s = response.toString();

        assertThat(s).contains("user@example.com").doesNotContain(SECRET_VALUE);
    }
}
