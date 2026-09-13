package com.aetherops.calendar;

import com.aetherops.common.exception.InvalidOAuthStateException;
import org.springframework.beans.factory.annotation.Value;
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
 * Signs the Calendar OAuth "state" param, the same way {@code com.aetherops.slack.SlackOAuthStateService}
 * does for the Slack install flow — a server-issued, time-limited, tamper-evident token bound
 * to (userId, tenantId, provider), so {@code POST .../calendar-connections} can't be tricked
 * into exchanging an authorization code that wasn't obtained through this app's own redirect
 * for this exact user/tenant/provider (OAuth authorization-code injection / CSRF, RFC 6749 §10.12).
 */
@Component
public class CalendarOAuthStateService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final long TTL_SECONDS = 600;

    private final SecretKeySpec keySpec;

    public CalendarOAuthStateService(@Value("${app.calendar.oauth-state-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("APP_CALENDAR_OAUTH_STATE_SECRET is required (must not be blank)");
        }
        this.keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String issue(UUID userId, UUID tenantId, CalendarProvider provider) {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        String payload = userId + ":" + tenantId + ":" + provider + ":" + expiresAt;
        String signature = hmacHex(payload);
        String raw = payload + ":" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Verifies signature + expiry, then checks the decoded identity matches what this connect request claims. */
    public void verify(String state, UUID userId, UUID tenantId, CalendarProvider provider) {
        if (state == null || state.isBlank()) {
            throw new InvalidOAuthStateException("Missing calendar OAuth state");
        }

        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidOAuthStateException("Malformed calendar OAuth state");
        }

        String[] parts = raw.split(":", 5);
        if (parts.length != 5) {
            throw new InvalidOAuthStateException("Malformed calendar OAuth state");
        }
        String rawUserId = parts[0];
        String rawTenantId = parts[1];
        String rawProvider = parts[2];
        long expiresAt;
        try {
            expiresAt = Long.parseLong(parts[3]);
        } catch (NumberFormatException e) {
            throw new InvalidOAuthStateException("Malformed calendar OAuth state");
        }
        String signature = parts[4];

        String expectedSignature = hmacHex(rawUserId + ":" + rawTenantId + ":" + rawProvider + ":" + expiresAt);
        if (!MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidOAuthStateException("Invalid calendar OAuth state signature");
        }
        if (Instant.now().getEpochSecond() > expiresAt) {
            throw new InvalidOAuthStateException("Calendar OAuth state expired");
        }
        if (!rawUserId.equals(userId.toString())
                || !rawTenantId.equals(tenantId.toString())
                || !rawProvider.equals(provider.name())) {
            throw new InvalidOAuthStateException("Calendar OAuth state does not match this request");
        }
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(keySpec);
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign calendar OAuth state", e);
        }
    }
}
