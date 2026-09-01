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

    private static final String SIGNING_SECRET = "test-signing-secret";

    private final SlackAppConfig config =
        new SlackAppConfig("client-id", "client-secret", SIGNING_SECRET, "http://localhost:8080/slack/oauth/callback");
    private final SlackOAuthStateService stateService = new SlackOAuthStateService(config);

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
        String expiredState = craftState(SIGNING_SECRET, tenantId, pastExpiry);

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
}
