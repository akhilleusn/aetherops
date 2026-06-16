package com.aetherops.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlidingWindowRateLimiterService {

    private final StringRedisTemplate redisTemplate;

    public boolean isAllowed(String key, int limit, int windowSeconds) {
        long nowMs = System.currentTimeMillis();
        long windowMs = (long) windowSeconds * 1000;
        long cutoffMs = nowMs - windowMs;
        String redisKey = "swrl:" + key;

        redisTemplate.opsForZSet().removeRangeByScore(redisKey, 0, cutoffMs);
        redisTemplate.opsForZSet().add(redisKey, String.valueOf(nowMs), nowMs);
        redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds + 1));

        Long count = redisTemplate.opsForZSet().zCard(redisKey);
        boolean allowed = count != null && count <= limit;

        if (!allowed) {
            log.warn("Sliding window rate limit exceeded key={} count={} limit={}", key, count, limit);
        }
        return allowed;
    }

    public long currentCount(String key, int windowSeconds) {
        long nowMs = System.currentTimeMillis();
        long windowMs = (long) windowSeconds * 1000;
        long cutoffMs = nowMs - windowMs;
        String redisKey = "swrl:" + key;

        Long count = redisTemplate.opsForZSet().count(redisKey, cutoffMs, nowMs);
        return count != null ? count + 1 : 1L;
    }
}
