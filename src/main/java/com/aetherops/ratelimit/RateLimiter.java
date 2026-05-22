package com.aetherops.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiter {

    public record Result(boolean allowed, int remaining, long resetAt) {}

    private static final class Bucket {
        int count;
        long resetAt;
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int limit;
    private final int windowSeconds;

    public RateLimiter(
        @Value("${app.rate-limit.requests-per-window}") int limit,
        @Value("${app.rate-limit.window-seconds}") int windowSeconds
    ) {
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    public Result tryConsume(String key) {
        long now = Instant.now().getEpochSecond();
        int[] count = {0};
        long[] resetAt = {0};

        buckets.compute(key, (k, existing) -> {
            Bucket b = (existing == null || now >= existing.resetAt) ? new Bucket() : existing;
            if (b != existing) {
                b.resetAt = now + windowSeconds;
                b.count = 0;
            }
            b.count = Math.min(b.count + 1, limit + 1);
            count[0] = b.count;
            resetAt[0] = b.resetAt;
            return b;
        });

        return new Result(
            count[0] <= limit,
            Math.max(0, limit - count[0]),
            resetAt[0]
        );
    }

    public int getLimit() {
        return limit;
    }
}
