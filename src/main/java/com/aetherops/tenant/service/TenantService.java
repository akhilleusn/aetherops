package com.aetherops.tenant.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.model.*;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantService {

    private static final String CACHE_PREFIX = "tenant:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final TenantRepository tenantRepository;
    private final RedisTemplate<String, TenantResponse> tenantCacheRedisTemplate;

    @Transactional
    public TenantResponse create(CreateTenantRequest request) {
        // Phase 2 remediation, finding 7 (see STEP_REPORT.md): plan and aiMonthlyBudgetCents
        // are no longer client-settable -- every new tenant starts on FREE with a zero AI
        // budget; upgrades happen exclusively through SubscriptionService's Stripe checkout
        // flow (webhook-driven, on confirmed payment), never through this endpoint.
        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .plan(Plan.FREE)
                .aiMonthlyBudgetCents(0)
                .retentionDays(request.getRetentionDays())
                .build();
        Tenant saved = tenantRepository.save(tenant);
        log.info("Created tenant id={} name={} plan={}", saved.getId(), saved.getName(), saved.getPlan());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public TenantResponse findById(UUID id) {
        String cacheKey = CACHE_PREFIX + id;

        TenantResponse cached = getCached(cacheKey, id);
        if (cached != null) {
            log.debug("Cache hit for tenant id={}", id);
            return cached;
        }

        log.debug("Cache miss for tenant id={}", id);
        TenantResponse response = tenantRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found: " + id));

        tenantCacheRedisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL);
        return response;
    }

    /**
     * Phase 2 remediation, finding 9 (see STEP_REPORT.md): a cache entry written under the
     * old, pre-this-change {@code GenericJackson2JsonRedisSerializer} format (which embedded a
     * {@code @class} discriminator) will not deserialize against the new
     * {@code Jackson2JsonRedisSerializer<TenantResponse>}, which expects the JSON to already
     * match {@link TenantResponse} directly. That's expected on the deploy that ships this
     * change -- {@code SerializationException} would otherwise propagate uncaught out of
     * {@code opsForValue().get(...)} and turn an ordinary cache read into a 500. Caught here and
     * treated as a cache miss instead, exactly like a key that was never cached: falls through
     * to the DB read below, which then overwrites the stale entry with the new format on the
     * next {@code set}. Self-healing within one TTL window (30 minutes) with no ops action
     * needed, and no request-facing failure at any point during the transition.
     */
    private TenantResponse getCached(String cacheKey, UUID id) {
        try {
            return tenantCacheRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("Failed to deserialize cached tenant response for id={} (stale cache " +
                "format from before the Jackson2JsonRedisSerializer<TenantResponse> migration " +
                "-- see STEP_REPORT.md) -- treating as a cache miss", id, e);
            return null;
        }
    }

    /**
     * Every tenant the caller belongs to — this codebase has exactly one tenant per user
     * (User.tenantId), so that's a single-element page, never every tenant in the system.
     * {@code callerTenantId} is null only if this is reached without authentication, which
     * production SecurityConfig doesn't allow; fail closed with an empty page rather than a
     * lookup exception.
     */
    @Transactional(readOnly = true)
    public Page<TenantResponse> findOwn(UUID callerTenantId, Pageable pageable) {
        if (callerTenantId == null) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        return tenantRepository.findById(callerTenantId)
            .map(tenant -> new PageImpl<>(List.of(toResponse(tenant)), pageable, 1))
            .orElseGet(() -> new PageImpl<>(List.of(), pageable, 0));
    }

    private TenantResponse toResponse(Tenant tenant) {
        return TenantResponse.builder()
                .id(tenant.getId())
                .name(tenant.getName())
                .plan(tenant.getPlan())
                .aiMonthlyBudgetCents(tenant.getAiMonthlyBudgetCents())
                .retentionDays(tenant.getRetentionDays())
                .createdAt(tenant.getCreatedAt())
                .updatedAt(tenant.getUpdatedAt())
                .build();
    }
}

