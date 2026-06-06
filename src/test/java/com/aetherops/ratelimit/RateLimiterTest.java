package com.aetherops.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static final int LIMIT  = 3;
    private static final int WINDOW = 60;

    private RedisServer redisServer;
    private RateLimiter rateLimiter;
    private LettuceConnectionFactory connectionFactory;

    @BeforeEach
    void setUp() throws Exception {
        redisServer = new RedisServer(6380);
        redisServer.start();

        connectionFactory = new LettuceConnectionFactory("localhost", 6380);
        connectionFactory.afterPropertiesSet();

        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        rateLimiter = new RateLimiter(redisTemplate, LIMIT, WINDOW);
    }

    @AfterEach
    void tearDown() throws Exception {
        connectionFactory.destroy();
        redisServer.stop();
    }

    @Test
    void firstRequest_isAllowed() {
        assertThat(rateLimiter.tryConsume("key-a").allowed()).isTrue();
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
        assertThat(rateLimiter.tryConsume("key-c").allowed()).isFalse();
    }

    @Test
    void remaining_decreasesWithEachRequest() {
        assertThat(rateLimiter.tryConsume("key-d").remaining()).isEqualTo(2);
        assertThat(rateLimiter.tryConsume("key-d").remaining()).isEqualTo(1);
        assertThat(rateLimiter.tryConsume("key-d").remaining()).isZero();
    }

    @Test
    void differentKeys_trackedIndependently() {
        for (int i = 0; i < LIMIT; i++) rateLimiter.tryConsume("key-g");
        RateLimiter.Result other = rateLimiter.tryConsume("key-h");
        assertThat(other.allowed()).isTrue();
        assertThat(other.remaining()).isEqualTo(LIMIT - 1);
    }
}