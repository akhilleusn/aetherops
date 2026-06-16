package com.aetherops.webhook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
@Slf4j
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";

    public String computeHmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
            mac.init(keySpec);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.error("Failed to compute HMAC-SHA256 signature", e);
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    public boolean verify(String secret, String payload, String receivedSignature) {
        String expected = computeHmacSha256(secret, payload);
        return expected.equals(receivedSignature);
    }
}
