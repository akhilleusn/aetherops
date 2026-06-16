package com.aetherops.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagService {

    private static final String TENANT_KEY_PREFIX = "feature:flags:";
    private static final String GLOBAL_KEY = "feature:flags:global";

    private final StringRedisTemplate redisTemplate;

    public boolean isEnabled(FeatureFlag flag, UUID tenantId) {
        String tenantKey = TENANT_KEY_PREFIX + tenantId;
        String tenantValue = (String) redisTemplate.opsForHash().get(tenantKey, flag.name());
        if (tenantValue != null) {
            log.debug("FeatureFlag {} for tenant={} overridden to {}", flag, tenantId, tenantValue);
            return "true".equalsIgnoreCase(tenantValue);
        }

        String globalValue = (String) redisTemplate.opsForHash().get(GLOBAL_KEY, flag.name());
        if (globalValue != null) {
            log.debug("FeatureFlag {} global value={}", flag, globalValue);
            return "true".equalsIgnoreCase(globalValue);
        }

        return true;
    }

    public void setTenantFlag(UUID tenantId, FeatureFlag flag, boolean enabled) {
        String tenantKey = TENANT_KEY_PREFIX + tenantId;
        redisTemplate.opsForHash().put(tenantKey, flag.name(), String.valueOf(enabled));
        log.info("Set feature flag {} = {} for tenant={}", flag, enabled, tenantId);
    }

    public void setGlobalFlag(FeatureFlag flag, boolean enabled) {
        redisTemplate.opsForHash().put(GLOBAL_KEY, flag.name(), String.valueOf(enabled));
        log.info("Set global feature flag {} = {}", flag, enabled);
    }

    public void clearTenantFlag(UUID tenantId, FeatureFlag flag) {
        String tenantKey = TENANT_KEY_PREFIX + tenantId;
        redisTemplate.opsForHash().delete(tenantKey, flag.name());
        log.info("Cleared feature flag {} override for tenant={}", flag, tenantId);
    }
}
