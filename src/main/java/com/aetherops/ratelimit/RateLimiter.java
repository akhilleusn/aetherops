package com.aetherops.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimiter {

    public record Result(boolean allowed, int remaining, long resetAt) {}

    private final StringRedisTemplate redisTemplate;
    private final int limit;
    private final int windowSeconds;

    public RateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${app.rate-limit.requests-per-window}") int limit,
            @Value("${app.rate-limit.window-seconds}") int windowSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public Result tryConsume(String key) {
        String redisKey = "ratelimit:" + key;

        Long count = redisTemplate.opsForValue().increment(redisKey);

        if (count == null) {
            count = 1L;
        }

        if (count == 1) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        long resetAt = System.currentTimeMillis() / 1000 + (ttl != null && ttl > 0 ? ttl : windowSeconds);

        int remaining = (int) Math.max(0, limit - count);
        boolean allowed = count <= limit;

        return new Result(allowed, remaining, resetAt);
    }

    public Result peek(String key) {
        String redisKey = "ratelimit:" + key;
        String raw = redisTemplate.opsForValue().get(redisKey);
        long count = raw != null ? Long.parseLong(raw) : 0L;
        Long ttl = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
        long resetAt = System.currentTimeMillis() / 1000 + (ttl != null && ttl > 0 ? ttl : windowSeconds);
        int remaining = (int) Math.max(0, limit - count);
        return new Result(count <= limit, remaining, resetAt);
    }

    public int getLimit() {
        return limit;
    }
}