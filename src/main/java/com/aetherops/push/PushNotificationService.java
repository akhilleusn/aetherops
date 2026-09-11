package com.aetherops.push;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private static final String EXPO_PUSH_URL = "https://exp.host/--/api/v2/push/send";

    private final PushDeviceTokenRepository tokenRepository;
    private final RestClient.Builder restClientBuilder;

    @Transactional
    public PushDeviceToken registerToken(UUID userId, UUID tenantId, String expoPushToken, String platform) {
        // Scoped by (tenantId, expoPushToken), matching the DB's unique index (V67) and the
        // RLS policy's own tenant scoping -- a lookup by token alone would be blind to another
        // tenant's registration of the same physical device (RLS hides it), which used to fall
        // through into an INSERT that then collided with it anyway. See STEP_REPORT.md.
        PushDeviceToken token = tokenRepository.findByTenantIdAndExpoPushToken(tenantId, expoPushToken)
            .orElseGet(() -> PushDeviceToken.builder().expoPushToken(expoPushToken).build());
        token.setUserId(userId);
        token.setTenantId(tenantId);
        token.setPlatform(platform);

        PushDeviceToken saved = tokenRepository.save(token);
        log.info("Registered push device token userId={} tenantId={} platform={}", userId, tenantId, platform);
        return saved;
    }

    /**
     * Best-effort — a failed/unreachable Expo push service must never block incident paging.
     * Returns whether the provider accepted the send (Step G1, see STEP_REPORT.md — this used
     * to be {@code void}, so callers had no way to know push failed either, same shape of
     * problem as the Twilio SMS bug this batch fixes, just lower stakes since it's explicitly
     * best-effort). {@code false} covers both "no devices registered" and a failed/unreachable
     * request — either way, nothing was delivered via push.
     */
    @Transactional(readOnly = true)
    public boolean sendPageAlert(UUID tenantId, String title, String body) {
        List<PushDeviceToken> tokens = tokenRepository.findByTenantId(tenantId);
        if (tokens.isEmpty()) {
            return false;
        }

        List<Map<String, Object>> messages = tokens.stream()
            .map(t -> Map.<String, Object>of(
                "to", t.getExpoPushToken(),
                "title", title,
                "body", body,
                "sound", "default",
                "priority", "high"))
            .toList();

        try {
            restClientBuilder.build().post()
                .uri(EXPO_PUSH_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(messages)
                .retrieve()
                .toBodilessEntity();
            log.info("Sent push page alert tenantId={} deviceCount={}", tenantId, tokens.size());
            return true;
        } catch (Exception e) {
            log.warn("Failed to send push page alert tenantId={}", tenantId, e);
            return false;
        }
    }
}
