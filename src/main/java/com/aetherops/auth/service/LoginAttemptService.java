package com.aetherops.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Phase 1 Step C, item 3: was an in-memory ConcurrentHashMap, meaning each pod tracked its own
 * attempt counts — N replicas gave an attacker N times the allowed attempts before any of them
 * would lock. Backed by Redis instead, so the count is shared cluster-wide.
 *
 * The failure count and the lock are the same Redis key with one TTL: every failure refreshes
 * the TTL to lockDuration, so an account with fewer than MAX_ATTEMPTS failures now forgets them
 * after lockDuration of inactivity (the in-memory version never expired a sub-threshold count —
 * an intentional behavior change, required by "keys expiring automatically").
 *
 * Fails CLOSED: if Redis is unreachable, isLocked() returns true (deny) rather than silently
 * disabling brute-force protection for the duration of the outage.
 */
@Component
@Slf4j
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final String KEY_PREFIX = "login-lock:";

    private final Duration lockDuration;
    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(
            @Value("${app.login.lock-duration-minutes:15}") long lockDurationMinutes,
            StringRedisTemplate redisTemplate) {
        this.lockDuration = Duration.ofMinutes(lockDurationMinutes);
        this.redisTemplate = redisTemplate;
    }

    public boolean isLocked(String email) {
        try {
            String value = redisTemplate.opsForValue().get(key(email));
            return value != null && Integer.parseInt(value) >= MAX_ATTEMPTS;
        } catch (Exception e) {
            log.error("Redis unavailable while checking login lock for email — failing closed (denying)", e);
            return true;
        }
    }

    public long minutesUntilUnlock(String email) {
        try {
            Long seconds = redisTemplate.getExpire(key(email), TimeUnit.SECONDS);
            if (seconds == null || seconds <= 0) return 1;
            return (seconds + 59) / 60;
        } catch (Exception e) {
            log.error("Redis unavailable while computing login lock remaining time", e);
            return lockDuration.toMinutes();
        }
    }

    public void recordFailure(String email) {
        try {
            String redisKey = key(email);
            Long count = redisTemplate.opsForValue().increment(redisKey);
            redisTemplate.expire(redisKey, lockDuration);
            if (count != null && count >= MAX_ATTEMPTS) {
                log.warn("Login locked after {} failed attempts", count);
            }
        } catch (Exception e) {
            // Swallowed deliberately — a Redis blip here must not break login. But silent
            // swallowing means a flapping Redis grants extra attempts with no trace, so this
            // has to be visible: a failed increment here is exactly the window where an
            // attacker gets a free attempt that never gets counted.
            log.warn("Redis unavailable while recording login failure for email — this attempt will not count toward the lockout threshold", e);
        }
    }

    public void recordSuccess(String email) {
        try {
            redisTemplate.delete(key(email));
        } catch (Exception e) {
            // Swallowed deliberately — must not fail an already-successful login. Visible
            // consequence: the failure counter for this email won't be cleared, so a
            // legitimate user could see a stale lock sooner than expected next time.
            log.warn("Redis unavailable while clearing login attempt count for email — stale failure count may persist", e);
        }
    }

    private String key(String email) {
        return KEY_PREFIX + email;
    }
}
