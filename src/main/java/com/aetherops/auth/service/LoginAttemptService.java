package com.aetherops.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;

    private final Duration lockDuration;
    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public LoginAttemptService(
            @Value("${app.login.lock-duration-minutes:15}") long lockDurationMinutes) {
        this.lockDuration = Duration.ofMinutes(lockDurationMinutes);
    }

    private record AttemptRecord(int count, Instant lockedUntil) {}

    public boolean isLocked(String email) {
        AttemptRecord record = attempts.get(email);
        if (record == null || record.lockedUntil() == null) return false;
        if (Instant.now().isAfter(record.lockedUntil())) {
            attempts.remove(email);
            return false;
        }
        return true;
    }

    public long minutesUntilUnlock(String email) {
        AttemptRecord record = attempts.get(email);
        if (record == null || record.lockedUntil() == null) return 1;
        long seconds = Duration.between(Instant.now(), record.lockedUntil()).toSeconds();
        if (seconds <= 0) return 1;
        return (seconds + 59) / 60;
    }

    public void recordFailure(String email) {
        attempts.compute(email, (key, existing) -> {
            // If the existing lock has expired, start fresh
            if (existing != null && existing.lockedUntil() != null
                    && !Instant.now().isBefore(existing.lockedUntil())) {
                existing = null;
            }
            int count = (existing == null ? 0 : existing.count()) + 1;
            Instant lockedUntil = count >= MAX_ATTEMPTS ? Instant.now().plus(lockDuration) : null;
            return new AttemptRecord(count, lockedUntil);
        });
    }

    public void recordSuccess(String email) {
        attempts.remove(email);
    }
}
