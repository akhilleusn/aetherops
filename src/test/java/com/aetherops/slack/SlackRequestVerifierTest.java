package com.aetherops.slack;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class SlackRequestVerifierTest {

    private static final String SIGNING_SECRET = "test-signing-secret";

    private final SlackAppConfig config =
        new SlackAppConfig("client-id", "client-secret", SIGNING_SECRET, "http://localhost:8080/slack/oauth/callback");
    private final SlackRequestVerifier verifier = new SlackRequestVerifier(config);

    // Independently computed — does not reuse SlackRequestVerifier's own HMAC code,
    // so a bug in the class under test can't cancel itself out in the assertion.
    private static String independentSignature(String secret, String timestamp, String body) {
        try {
            String base = "v0:" + timestamp + ":" + body;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(base.getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void verify_returnsTrue_forValidSignature() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "command=%2Foncall&text=payment-svc&team_id=T123";
        String signature = independentSignature(SIGNING_SECRET, timestamp, body);

        assertThat(verifier.verify(timestamp, signature, body.getBytes(StandardCharsets.UTF_8))).isTrue();
    }

    @Test
    void verify_returnsFalse_forTamperedBody() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "command=%2Foncall&text=payment-svc&team_id=T123";
        String signature = independentSignature(SIGNING_SECRET, timestamp, body);

        String tamperedBody = "command=%2Fpage&text=payment-svc&team_id=T123";
        assertThat(verifier.verify(timestamp, signature, tamperedBody.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void verify_returnsFalse_forWrongSigningSecret() {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "command=%2Foncall&text=payment-svc&team_id=T123";
        String signature = independentSignature("a-different-secret", timestamp, body);

        assertThat(verifier.verify(timestamp, signature, body.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void verify_returnsFalse_forExpiredTimestamp() {
        String staleTimestamp = String.valueOf(Instant.now().getEpochSecond() - 3600);
        String body = "command=%2Foncall&text=payment-svc&team_id=T123";
        String signature = independentSignature(SIGNING_SECRET, staleTimestamp, body);

        assertThat(verifier.verify(staleTimestamp, signature, body.getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void verify_returnsFalse_forMissingHeaders() {
        byte[] body = "command=%2Foncall".getBytes(StandardCharsets.UTF_8);
        assertThat(verifier.verify(null, "v0=abc", body)).isFalse();
        assertThat(verifier.verify("12345", null, body)).isFalse();
    }

    @Test
    void verify_returnsFalse_whenSigningSecretNotConfigured() {
        SlackAppConfig unconfigured = new SlackAppConfig("", "", "", "http://localhost:8080/slack/oauth/callback");
        SlackRequestVerifier unconfiguredVerifier = new SlackRequestVerifier(unconfigured);

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        byte[] body = "command=%2Foncall".getBytes(StandardCharsets.UTF_8);

        assertThat(unconfiguredVerifier.verify(timestamp, "v0=abc", body)).isFalse();
    }
}
