package com.aetherops.auth;

import com.aetherops.AbstractIntegrationTest;
import com.aetherops.auth.service.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 Step C, item 3: LoginAttemptService moved from an in-memory ConcurrentHashMap to
 * Redis, so this now needs a real Redis (via AbstractIntegrationTest's Testcontainers instance)
 * rather than being a pure in-process unit test.
 */
@ActiveProfiles("test")
class LoginAttemptServiceTest extends AbstractIntegrationTest {

    @Autowired StringRedisTemplate redisTemplate;

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(15, redisTemplate);
    }

    private String uniqueEmail() {
        return UUID.randomUUID() + "@test.com";
    }

    @Test
    void newEmail_isNotLocked() {
        assertThat(service.isLocked(uniqueEmail())).isFalse();
    }

    @Test
    void fourFailures_notYetLocked() {
        String email = uniqueEmail();
        for (int i = 0; i < 4; i++) service.recordFailure(email);
        assertThat(service.isLocked(email)).isFalse();
    }

    @Test
    void fiveFailures_isLocked() {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) service.recordFailure(email);
        assertThat(service.isLocked(email)).isTrue();
    }

    @Test
    void lockedAccount_minutesUntilUnlock_isPositive() {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) service.recordFailure(email);
        assertThat(service.minutesUntilUnlock(email)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void successResetsCounter() {
        String email = uniqueEmail();
        for (int i = 0; i < 4; i++) service.recordFailure(email);
        service.recordSuccess(email);
        // 4 more failures after reset — must not be locked yet
        for (int i = 0; i < 4; i++) service.recordFailure(email);
        assertThat(service.isLocked(email)).isFalse();
        // 5th failure since reset — now locked
        service.recordFailure(email);
        assertThat(service.isLocked(email)).isTrue();
    }

    @Test
    void expiredLock_isNotLocked() throws InterruptedException {
        LoginAttemptService shortService = new LoginAttemptService(0, redisTemplate);
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) shortService.recordFailure(email);
        Thread.sleep(50);
        assertThat(shortService.isLocked(email)).isFalse();
    }
}
