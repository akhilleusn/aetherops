package com.aetherops.calendar;

import com.aetherops.common.exception.InvalidOAuthStateException;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the OAuth "state" check on the calendar-connect flow actually blocks authorization-code
 * injection / OAuth CSRF (RFC 6749 §10.12): a state minted for one user/tenant/provider can't be
 * replayed against another, can't be forged without the signing secret, and can't be replayed
 * after it expires.
 */
class CalendarOAuthStateServiceTest {

    private static final String SECRET = "test-calendar-oauth-state-secret";

    private final CalendarOAuthStateService stateService = new CalendarOAuthStateService(SECRET);

    private static String independentHmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String craftState(String secret, UUID userId, UUID tenantId, CalendarProvider provider, long expiresAtEpochSeconds) {
        String payload = userId + ":" + tenantId + ":" + provider + ":" + expiresAtEpochSeconds;
        String signature = independentHmac(secret, payload);
        String raw = payload + ":" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void issueThenVerify_sameUserTenantProvider_succeeds() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String state = stateService.issue(userId, tenantId, CalendarProvider.GOOGLE);

        assertThatCode(() -> stateService.verify(state, userId, tenantId, CalendarProvider.GOOGLE))
            .doesNotThrowAnyException();
    }

    @Test
    void verify_stateIssuedForDifferentUser_rejected() {
        UUID issuedForUserId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String state = stateService.issue(issuedForUserId, tenantId, CalendarProvider.GOOGLE);

        UUID attackerUserId = UUID.randomUUID();

        // A state minted for one user can't be replayed by/for a different user — this is
        // exactly what stops an attacker from capturing a valid authorization code + state
        // pair and replaying it against their own (or someone else's) userId.
        assertThatThrownBy(() -> stateService.verify(state, attackerUserId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void verify_stateIssuedForDifferentTenant_rejected() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String state = stateService.issue(userId, tenantId, CalendarProvider.GOOGLE);

        assertThatThrownBy(() -> stateService.verify(state, userId, UUID.randomUUID(), CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void verify_stateIssuedForDifferentProvider_rejected() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String state = stateService.issue(userId, tenantId, CalendarProvider.GOOGLE);

        assertThatThrownBy(() -> stateService.verify(state, userId, tenantId, CalendarProvider.OUTLOOK))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void verify_rejectsTamperedIdentity() {
        long futureExpiry = Instant.now().getEpochSecond() + 600;
        UUID tenantId = UUID.randomUUID();

        // Attacker swaps in their own userId but can't produce a valid signature for it
        // without knowing the signing secret.
        UUID attackerUserId = UUID.randomUUID();
        String forgedPayload = attackerUserId + ":" + tenantId + ":" + CalendarProvider.GOOGLE + ":" + futureExpiry;
        String tamperedRaw = forgedPayload + ":" + independentHmac("wrong-secret-attacker-doesnt-know", forgedPayload);
        String tamperedState = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tamperedRaw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> stateService.verify(tamperedState, attackerUserId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void verify_rejectsExpiredState() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        long pastExpiry = Instant.now().getEpochSecond() - 60;
        String expiredState = craftState(SECRET, userId, tenantId, CalendarProvider.GOOGLE, pastExpiry);

        assertThatThrownBy(() -> stateService.verify(expiredState, userId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void verify_rejectsMalformedState() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> stateService.verify("not-valid-base64!!!", userId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void verify_rejectsMissingState() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        assertThatThrownBy(() -> stateService.verify(null, userId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
        assertThatThrownBy(() -> stateService.verify("", userId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void verify_rejectsStateSignedWithDifferentSecret() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        long futureExpiry = Instant.now().getEpochSecond() + 600;
        String state = craftState("a-different-secret-entirely", userId, tenantId, CalendarProvider.GOOGLE, futureExpiry);

        assertThatThrownBy(() -> stateService.verify(state, userId, tenantId, CalendarProvider.GOOGLE))
            .isInstanceOf(InvalidOAuthStateException.class);
    }

    @Test
    void constructor_rejectsBlankSecret() {
        assertThatThrownBy(() -> new CalendarOAuthStateService(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarOAuthStateService(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
