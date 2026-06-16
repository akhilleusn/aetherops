package com.aetherops.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlidingWindowRateLimiterServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ZSetOperations<String, String> zSetOps;
    @InjectMocks private SlidingWindowRateLimiterService service;

    @Test
    void isAllowed_returnTrue_whenUnderLimit() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOps.zCard(anyString())).thenReturn(3L);

        boolean result = service.isAllowed("test-key", 5, 60);

        assertThat(result).isTrue();
    }

    @Test
    void isAllowed_returnFalse_whenOverLimit() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOps.zCard(anyString())).thenReturn(6L);

        boolean result = service.isAllowed("test-key", 5, 60);

        assertThat(result).isFalse();
    }

    @Test
    void isAllowed_exactlyAtLimit_isAllowed() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(0L);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOps.zCard(anyString())).thenReturn(5L);

        boolean result = service.isAllowed("test-key", 5, 60);

        assertThat(result).isTrue();
    }

    @Test
    void isAllowed_removesExpiredEntries() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.removeRangeByScore(anyString(), anyDouble(), anyDouble())).thenReturn(2L);
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(true);
        when(zSetOps.zCard(anyString())).thenReturn(1L);

        service.isAllowed("test-key", 5, 60);

        verify(zSetOps).removeRangeByScore(eq("swrl:test-key"), eq(0.0), anyDouble());
    }
}
