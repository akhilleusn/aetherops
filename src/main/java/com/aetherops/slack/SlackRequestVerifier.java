package com.aetherops.slack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Verifies Slack's v0 request signature (see api.slack.com/authentication/verifying-requests-from-slack). */
@Component
@RequiredArgsConstructor
@Slf4j
public class SlackRequestVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;

    private final SlackAppConfig config;

    public boolean verify(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || signatureHeader == null || config.getSigningSecret().isBlank()) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > MAX_CLOCK_SKEW_SECONDS) {
            log.warn("Rejected Slack request: timestamp outside allowed skew");
            return false;
        }

        String base = "v0:" + timestampHeader + ":" + new String(rawBody, StandardCharsets.UTF_8);
        String expected = "v0=" + hmacHex(base);

        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(config.getSigningSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute Slack request signature", e);
        }
    }
}
