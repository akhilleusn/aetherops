package com.aetherops.tenant.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.tenant.model.Plan;
import com.aetherops.tenant.model.Tenant;
import com.aetherops.tenant.model.TenantResponse;
import com.aetherops.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 2 remediation, finding 9 (see STEP_REPORT.md): {@code tenantCacheRedisTemplate} is now
 * scoped to {@link TenantResponse} via {@code Jackson2JsonRedisSerializer}, replacing the old
 * {@code GenericJackson2JsonRedisSerializer}-backed bean. A cache entry written under that old
 * format will fail to deserialize under the new one — this must surface as a cache miss, never
 * an uncaught exception out of an ordinary read.
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock private TenantRepository tenantRepository;
    @Mock private RedisTemplate<String, TenantResponse> tenantCacheRedisTemplate;
    @Mock private ValueOperations<String, TenantResponse> valueOperations;

    @InjectMocks
    private TenantService service;

    @Test
    void findById_cacheHit_returnsCachedValueWithoutHittingRepository() {
        UUID id = UUID.randomUUID();
        TenantResponse cached = TenantResponse.builder().id(id).name("Cached Co").plan(Plan.FREE).build();
        when(tenantCacheRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("tenant:" + id)).thenReturn(cached);

        TenantResponse result = service.findById(id);

        assertThat(result).isEqualTo(cached);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void findById_staleCacheFormatDeserializationFailure_fallsThroughToRepository_notThrown() {
        UUID id = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(id).name("DB Co").plan(Plan.STARTER)
            .aiMonthlyBudgetCents(0).retentionDays(30).build();
        when(tenantCacheRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // Simulates a value written under the old GenericJackson2JsonRedisSerializer (@class
        // discriminator) format, unreadable by the new type-scoped serializer.
        when(valueOperations.get("tenant:" + id)).thenThrow(new SerializationException("Could not read JSON"));
        when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));

        TenantResponse result = service.findById(id);

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getName()).isEqualTo("DB Co");
        verify(tenantRepository).findById(id);
        // The stale entry is overwritten with the new format on this read, self-healing the key.
        verify(valueOperations).set(eq("tenant:" + id), any(TenantResponse.class), any());
    }

    @Test
    void findById_cacheMiss_populatesCacheAfterRepositoryRead() {
        UUID id = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(id).name("Fresh Co").plan(Plan.FREE)
            .aiMonthlyBudgetCents(0).retentionDays(30).build();
        when(tenantCacheRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("tenant:" + id)).thenReturn(null);
        when(tenantRepository.findById(id)).thenReturn(Optional.of(tenant));

        TenantResponse result = service.findById(id);

        assertThat(result.getName()).isEqualTo("Fresh Co");
        verify(valueOperations).set(eq("tenant:" + id), any(TenantResponse.class), any());
    }

    @Test
    void findById_notFoundInCacheOrDb_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(tenantCacheRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("tenant:" + id)).thenReturn(null);
        when(tenantRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id)).isInstanceOf(ResourceNotFoundException.class);
    }
}
