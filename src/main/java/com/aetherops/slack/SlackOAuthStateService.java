package com.aetherops.slack;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Signs the OAuth "state" param so the /slack/oauth/callback redirect (a plain
 * browser GET, not something Slack HMAC-signs) can't be forged to link a
 * workspace to an attacker-chosen tenant.
 */
@Component
@RequiredArgsConstructor
public class SlackOAuthStateService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long TTL_SECONDS = 600;

    private final SlackAppConfig config;

    public String issue(UUID tenantId) {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = tenantId + ":" + expiresAt;
        String signature = hmacHex(payload);
        String raw = payload + ":" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public UUID verify(String state) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed Slack OAuth state", e);
        }

        String[] parts = raw.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed Slack OAuth state");
        }
        String tenantId = parts[0];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed Slack OAuth state", e);
        }
        String signature = parts[2];

        String expectedSignature = hmacHex(tenantId + ":" + expiresAt);
        if (!MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid Slack OAuth state signature");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new IllegalArgumentException("Slack OAuth state expired");
        }
        try {
            return UUID.fromString(tenantId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed Slack OAuth state", e);
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(config.getSigningSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign Slack OAuth state", e);
        }
    }
}
