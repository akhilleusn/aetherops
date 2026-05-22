package com.aetherops.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final int LIMIT   = 3;
    private static final int WINDOW  = 60;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(LIMIT, WINDOW);
    }

    @Test
    void firstRequest_isAllowed() {
        RateLimiter.Result result = rateLimiter.tryConsume("key-a");
        assertThat(result.allowed()).isTrue();
    }

    @Test
    void requestAtLimit_isAllowed() {
        rateLimiter.tryConsume("key-b");
        rateLimiter.tryConsume("key-b");
        RateLimiter.Result result = rateLimiter.tryConsume("key-b");
        assertThat(result.allowed()).isTrue();
        assertThat(result.remaining()).isZero();
    }

    @Test
    void requestOverLimit_isDenied() {
        for (int i = 0; i < LIMIT; i++) rateLimiter.tryConsume("key-c");
        RateLimiter.Result result = rateLimiter.tryConsume("key-c");
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void remaining_decreasesWithEachRequest() {
        assertThat(rateLimiter.tryConsume("key-d").remaining()).isEqualTo(2);
        assertThat(rateLimiter.tryConsume("key-d").remaining()).isEqualTo(1);
        assertThat(rateLimiter.tryConsume("key-d").remaining()).isZero();
    }

    @Test
    void overLimit_remainingIsAlwaysZero() {
        for (int i = 0; i < LIMIT + 3; i++) rateLimiter.tryConsume("key-e");
        assertThat(rateLimiter.tryConsume("key-e").remaining()).isZero();
    }

    @Test
    void resetAt_isApproximatelyNowPlusWindow() {
        long before = System.currentTimeMillis() / 1000;
        RateLimiter.Result result = rateLimiter.tryConsume("key-f");
        long after = System.currentTimeMillis() / 1000;
        assertThat(result.resetAt()).isBetween(before + WINDOW - 1, after + WINDOW + 1);
    }

    @Test
    void differentKeys_trackedIndependently() {
        for (int i = 0; i < LIMIT; i++) rateLimiter.tryConsume("key-g");
        RateLimiter.Result other = rateLimiter.tryConsume("key-h");
        assertThat(other.allowed()).isTrue();
        assertThat(other.remaining()).isEqualTo(LIMIT - 1);
    }

    @Test
    void windowExpiry_resetsCount() throws InterruptedException {
        RateLimiter shortWindow = new RateLimiter(LIMIT, 1);
        for (int i = 0; i < LIMIT; i++) shortWindow.tryConsume("key-i");
        assertThat(shortWindow.tryConsume("key-i").allowed()).isFalse();

        Thread.sleep(1100);

        assertThat(shortWindow.tryConsume("key-i").allowed()).isTrue();
    }
}
