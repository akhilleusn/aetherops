package com.aetherops.slack;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlackOAuthStateServiceTest {

    // Deliberately different from SIGNING_SECRET below -- Phase 3 finding 4 (see
    // STEP_REPORT.md) gave this service its own dedicated key, separate from
    // app.slack.signing-secret (the Slack-provided value SlackRequestVerifier uses to verify
    // INBOUND requests). Using two different values here proves the two are no longer coupled.
    private static final String OAUTH_STATE_SECRET = "test-slack-oauth-state-secret";
    private static final String SIGNING_SECRET = "test-signing-secret";

    private final SlackOAuthStateService stateService = new SlackOAuthStateService(OAUTH_STATE_SECRET);

    private static String independentHmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String craftState(String secret, UUID tenantId, long expiresAtEpochSeconds) {
        String payload = tenantId + ":" + expiresAtEpochSeconds;
        String signature = independentHmac(secret, payload);
        String raw = payload + ":" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void issueThenVerify_roundTrip_returnsSameTenantId() {
        UUID tenantId = UUID.randomUUID();
        String state = stateService.issue(tenantId);
        assertThat(stateService.verify(state)).isEqualTo(tenantId);
    }

    /**
     * Phase 3 finding 4: proves the state token validates correctly under the new dedicated
     * key, and — the actual point of the fix — a state forged with what used to be the shared
     * app.slack.signing-secret value no longer validates. Before this fix, this exact input
     * would have passed verification.
     */
    @Test
    void verify_rejectsStateSignedWithSlackSigningSecret_provingKeysAreIndependent() {
        UUID tenantId = UUID.randomUUID();
        long futureExpiry = Instant.now().getEpochSecond() + 600;
        String stateSignedWithSigningSecret = craftState(SIGNING_SECRET, tenantId, futureExpiry);

        assertThatThrownBy(() -> stateService.verify(stateSignedWithSigningSecret))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_rejectsTamperedTenantId() {
        long futureExpiry = Instant.now().getEpochSecond() + 600;

        // Attacker swaps in their own tenantId but can't produce a valid signature
        // for it without knowing the signing secret.
        UUID attackerTenantId = UUID.randomUUID();
        String forgedPayload = attackerTenantId + ":" + futureExpiry;
        String tamperedRaw = forgedPayload + ":" + independentHmac("wrong-secret-attacker-doesnt-know", forgedPayload);
        String tamperedState = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(tamperedRaw.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> stateService.verify(tamperedState))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_rejectsExpiredState() {
        UUID tenantId = UUID.randomUUID();
        long pastExpiry = Instant.now().getEpochSecond() - 60;
        String expiredState = craftState(OAUTH_STATE_SECRET, tenantId, pastExpiry);

        assertThatThrownBy(() -> stateService.verify(expiredState))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void verify_rejectsMalformedState() {
        assertThatThrownBy(() -> stateService.verify("not-valid-base64!!!"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verify_rejectsStateSignedWithDifferentSecret() {
        UUID tenantId = UUID.randomUUID();
        long futureExpiry = Instant.now().getEpochSecond() + 600;
        String state = craftState("a-different-secret-entirely", tenantId, futureExpiry);

        assertThatThrownBy(() -> stateService.verify(state))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsBlankSecret() {
        assertThatThrownBy(() -> new SlackOAuthStateService(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlackOAuthStateService(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
