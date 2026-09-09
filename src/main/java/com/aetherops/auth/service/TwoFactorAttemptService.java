package com.aetherops.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Phase 1 Step C, item 2: a 6-digit TOTP code (1,000,000 possibilities) is brute-forceable
 * once an attacker already has a valid password — LoginAttemptService only guards login itself,
 * not the 2FA step after it. This locks the SESSION (identified by its partial token), not the
 * account, after 5 failed code attempts — recovery is simply logging in again, which mints a
 * fresh partial token and thus a fresh counter.
 *
 * Redis-backed (not in-memory) for the same reason as LoginAttemptService: this must hold
 * across replicas, or N pods give N times the allowed attempts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwoFactorAttemptService {

    private static final int MAX_ATTEMPTS = 5;

    // Partial tokens live 5 minutes (see JwtService.generatePartialToken) — the counter's TTL
    // matches that, so it never outlives the token it's guarding and needs no separate cleanup.
    private static final Duration COUNTER_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public boolean isLocked(String partialToken) {
        try {
            String value = redisTemplate.opsForValue().get(key(partialToken));
            return value != null && Integer.parseInt(value) >= MAX_ATTEMPTS;
        } catch (Exception e) {
            // Fail closed: if Redis can't confirm this session isn't locked, treat it as locked.
            log.error("Redis unavailable while checking 2FA attempt lock — failing closed (denying)", e);
            return true;
        }
    }

    public void recordFailure(String partialToken) {
        try {
            String redisKey = key(partialToken);
            Long count = redisTemplate.opsForValue().increment(redisKey);
            redisTemplate.expire(redisKey, COUNTER_TTL);
            if (count != null && count >= MAX_ATTEMPTS) {
                log.warn("2FA session locked after {} failed code attempts", count);
            }
        } catch (Exception e) {
            // Swallowed deliberately — a Redis blip here must not break 2FA verification. But
            // silent swallowing means a flapping Redis grants extra attempts with no trace:
            // this specific failed code attempt won't count toward the lockout threshold.
            log.warn("Redis unavailable while recording 2FA attempt failure — this attempt will not count toward the lockout threshold", e);
        }
    }

    private String key(String partialToken) {
        return "2fa-lock:" + sha256(partialToken);
    }

    private static String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
