package com.aetherops.auth;

import com.aetherops.auth.service.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService(15);
    }

    @Test
    void newEmail_isNotLocked() {
        assertThat(service.isLocked("a@test.com")).isFalse();
    }

    @Test
    void fourFailures_notYetLocked() {
        for (int i = 0; i < 4; i++) service.recordFailure("b@test.com");
        assertThat(service.isLocked("b@test.com")).isFalse();
    }

    @Test
    void fiveFailures_isLocked() {
        for (int i = 0; i < 5; i++) service.recordFailure("c@test.com");
        assertThat(service.isLocked("c@test.com")).isTrue();
    }

    @Test
    void lockedAccount_minutesUntilUnlock_isPositive() {
        for (int i = 0; i < 5; i++) service.recordFailure("d@test.com");
        assertThat(service.minutesUntilUnlock("d@test.com")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void successResetsCounter() {
        for (int i = 0; i < 4; i++) service.recordFailure("e@test.com");
        service.recordSuccess("e@test.com");
        // 4 more failures after reset — must not be locked yet
        for (int i = 0; i < 4; i++) service.recordFailure("e@test.com");
        assertThat(service.isLocked("e@test.com")).isFalse();
        // 5th failure since reset — now locked
        service.recordFailure("e@test.com");
        assertThat(service.isLocked("e@test.com")).isTrue();
    }

    @Test
    void expiredLock_isNotLocked() throws InterruptedException {
        LoginAttemptService shortService = new LoginAttemptService(0);
        for (int i = 0; i < 5; i++) shortService.recordFailure("f@test.com");
        Thread.sleep(10);
        assertThat(shortService.isLocked("f@test.com")).isFalse();
    }
}
