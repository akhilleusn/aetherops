package com.aetherops.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private HashOperations<String, Object, Object> hashOps;
    @InjectMocks private FeatureFlagService service;

    @Test
    void isEnabled_tenantOverride_overridesGlobal() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("feature:flags:" + tenantId, FeatureFlag.AI_ANALYSIS.name()))
                .thenReturn("false");

        boolean result = service.isEnabled(FeatureFlag.AI_ANALYSIS, tenantId);

        assertThat(result).isFalse();
        verify(hashOps, never()).get(eq("feature:flags:global"), any());
    }

    @Test
    void isEnabled_fallsBackToGlobal_whenNoTenantOverride() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("feature:flags:" + tenantId, FeatureFlag.WEBHOOKS.name()))
                .thenReturn(null);
        when(hashOps.get("feature:flags:global", FeatureFlag.WEBHOOKS.name()))
                .thenReturn("false");

        boolean result = service.isEnabled(FeatureFlag.WEBHOOKS, tenantId);

        assertThat(result).isFalse();
    }

    @Test
    void isEnabled_defaultsToTrue_whenNothingConfigured() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), any())).thenReturn(null);

        boolean result = service.isEnabled(FeatureFlag.RATE_LIMITING, tenantId);

        assertThat(result).isTrue();
    }

    @Test
    void isEnabled_tenantEnabledOverride_returnsTrue() {
        UUID tenantId = UUID.randomUUID();
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get("feature:flags:" + tenantId, FeatureFlag.KAFKA_PUBLISHING.name()))
                .thenReturn("true");

        boolean result = service.isEnabled(FeatureFlag.KAFKA_PUBLISHING, tenantId);

        assertThat(result).isTrue();
    }
}
