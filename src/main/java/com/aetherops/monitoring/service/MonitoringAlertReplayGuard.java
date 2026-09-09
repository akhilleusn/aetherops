package com.aetherops.monitoring.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Phase 1 Step E, item 2 (finding 5) — narrow, low-value-but-cheap replay guard for the five
 * STATIC_TOKEN webhook providers (no cryptographic signature, so no signed timestamp to check
 * a replay window against the way Grafana/Slack do). Deliberately keyed on (tenant, provider,
 * alert fingerprint) — the SAME fingerprint MonitoringAlertIngestionService already computes
 * for find-or-create — and NOT on a hash of the raw body: these providers legitimately resend
 * byte-identical payloads while an alert condition persists (e.g. Datadog re-firing on an
 * still-unresolved monitor), and a body-hash key would silently drop those genuine re-fires.
 *
 * The window is short (default 30s, well below any real provider's re-fire interval, which is
 * minutes at the fastest) specifically so it only catches a near-immediate resend of a
 * captured request, not a legitimate periodic re-fire.
 *
 * As noted in STEP_REPORT.md: the actual security value here is limited. The existing
 * find-or-create-against-open-incidents path in MonitoringAlertIngestionService already
 * absorbs the common case (a replay while the incident is open just bumps eventCount, harmless
 * noise). The one thing this guard adds is stopping a replay from reopening/recreating an
 * incident that already resolved — itself closer to noise than compromise. Kept because it
 * turned out cheap to add (no changes to AlertSignal or its 13 call sites), not because it's
 * high-value on its own.
 *
 * Fails OPEN on a Redis error: unlike login-lockout-style guards, wrongly letting a legitimate
 * alert page someone is far worse than occasionally missing a low-value replay check, so a
 * Redis outage must never block real alerts.
 */
@Component
@Slf4j
public class MonitoringAlertReplayGuard {

    private static final String KEY_PREFIX = "webhook-replay:";

    private final Duration window;
    private final StringRedisTemplate redisTemplate;

    public MonitoringAlertReplayGuard(
        @Value("${app.webhooks.replay-window-seconds:30}") long windowSeconds,
        StringRedisTemplate redisTemplate
    ) {
        this.window = Duration.ofSeconds(windowSeconds);
        this.redisTemplate = redisTemplate;
    }

    public boolean isReplay(UUID tenantId, String provider, String fingerprint) {
        String key = KEY_PREFIX + tenantId + ":" + provider + ":" + fingerprint;
        try {
            Boolean firstSeen = redisTemplate.opsForValue().setIfAbsent(key, "1", window);
            boolean replay = firstSeen == null || !firstSeen;
            if (replay) {
                log.warn("Rejected replayed {} alert as a duplicate within the {}s window tenantId={} fingerprint={}",
                    provider, window.getSeconds(), tenantId, fingerprint);
            }
            return replay;
        } catch (Exception e) {
            log.warn("Redis unavailable while checking webhook replay — failing open (not treating as a replay) "
                + "provider={} tenantId={}", provider, tenantId, e);
            return false;
        }
    }
}
