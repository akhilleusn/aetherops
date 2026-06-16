package com.aetherops.apikey;

import com.aetherops.apikey.model.ApiKey;
import com.aetherops.apikey.repository.ApiKeyRepository;
import com.aetherops.apikey.service.ApiKeyService;
import com.aetherops.common.exception.InvalidApiKeyException;
import com.aetherops.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceExpiryTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private ProjectService projectService;
    @InjectMocks private ApiKeyService apiKeyService;

    @Test
    void resolve_throwsInvalidApiKeyException_whenKeyExpired() {
        ApiKey expiredKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .keyHash("any")
                .keyPrefix("ak_test12")
                .active(true)
                .expiresAt(Instant.now().minusSeconds(3600))
                .build();
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(expiredKey));

        assertThatThrownBy(() -> apiKeyService.resolve("ak_test12_abc"))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void resolve_returnsKey_whenNotExpired() {
        ApiKey validKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .keyHash("any")
                .keyPrefix("ak_valid123")
                .active(true)
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(validKey));

        ApiKey result = apiKeyService.resolve("ak_valid123_abc");
        assertThat(result.getId()).isEqualTo(validKey.getId());
    }

    @Test
    void resolve_returnsKey_whenNoExpiry() {
        ApiKey nonExpiringKey = ApiKey.builder()
                .id(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .keyHash("any")
                .keyPrefix("ak_noexp12")
                .active(true)
                .expiresAt(null)
                .build();
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.of(nonExpiringKey));

        ApiKey result = apiKeyService.resolve("ak_noexp12_abc");
        assertThat(result.getId()).isEqualTo(nonExpiringKey.getId());
    }

    @Test
    void resolve_throwsInvalidApiKeyException_whenKeyNotFound() {
        when(apiKeyRepository.findByKeyHashAndActiveTrue(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.resolve("ak_missing"))
                .isInstanceOf(InvalidApiKeyException.class);
    }
}
