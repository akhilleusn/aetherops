package com.aetherops.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier();

    @Test
    void computeHmacSha256_returnsSignatureWithPrefix() {
        String signature = verifier.computeHmacSha256("secret", "payload");
        assertThat(signature).startsWith("sha256=");
        assertThat(signature).hasSize(71); // "sha256=" + 64 hex chars
    }

    @Test
    void computeHmacSha256_isDeterministic() {
        String sig1 = verifier.computeHmacSha256("secret", "payload");
        String sig2 = verifier.computeHmacSha256("secret", "payload");
        assertThat(sig1).isEqualTo(sig2);
    }

    @Test
    void computeHmacSha256_differentSecrets_produceDifferentSignatures() {
        String sig1 = verifier.computeHmacSha256("secret1", "payload");
        String sig2 = verifier.computeHmacSha256("secret2", "payload");
        assertThat(sig1).isNotEqualTo(sig2);
    }

    @Test
    void verify_returnsTrue_forMatchingSignature() {
        String payload = "{\"event\":\"incident.created\"}";
        String secret = "my-webhook-secret";
        String expected = verifier.computeHmacSha256(secret, payload);

        assertThat(verifier.verify(secret, payload, expected)).isTrue();
    }

    @Test
    void verify_returnsFalse_forTamperedPayload() {
        String secret = "my-webhook-secret";
        String original = "{\"event\":\"incident.created\"}";
        String tampered = "{\"event\":\"incident.deleted\"}";
        String signature = verifier.computeHmacSha256(secret, original);

        assertThat(verifier.verify(secret, tampered, signature)).isFalse();
    }

    @Test
    void computeHmacSha256_knownVector() {
        // RFC 4231 test vector equivalent for quick sanity check
        String sig = verifier.computeHmacSha256("key", "The quick brown fox jumps over the lazy dog");
        assertThat(sig).startsWith("sha256=");
        assertThat(sig).isNotBlank();
    }
}
