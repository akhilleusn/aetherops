package com.aetherops.apikey.service;

import com.aetherops.apikey.model.*;
import com.aetherops.apikey.repository.ApiKeyRepository;
import com.aetherops.common.exception.InvalidApiKeyException;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.project.model.Project;
import com.aetherops.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;
    private final ProjectService   projectService;

    @Transactional
    public CreateApiKeyResponse create(UUID projectId, UUID tenantId, CreateApiKeyRequest request) {
        Project project = projectService.getForTenant(projectId, tenantId);

        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String encoded  = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String plaintext = "ak_" + encoded;          // 35 chars
        String keyPrefix = plaintext.substring(0, 12); // "ak_XXXXXXXXX"
        String keyHash   = sha256(plaintext);

        ApiKey key = ApiKey.builder()
            .tenantId(project.getTenantId())
            .projectId(project.getId())
            .description(request != null ? request.getDescription() : null)
            .keyPrefix(keyPrefix)
            .keyHash(keyHash)
            .active(true)
            .build();

        ApiKey saved = apiKeyRepository.save(key);
        log.info("Created API key id={} project={} tenant={}", saved.getId(), projectId, tenantId);

        return CreateApiKeyResponse.builder()
            .id(saved.getId())
            .projectId(saved.getProjectId())
            .keyPrefix(saved.getKeyPrefix())
            .plaintext(plaintext)
            .description(saved.getDescription())
            .createdAt(saved.getCreatedAt())
            .build();
    }

    @Transactional(readOnly = true)
    public List<ApiKeyView> list(UUID projectId, UUID tenantId) {
        projectService.getForTenant(projectId, tenantId);
        return apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
            .stream().map(this::toView).toList();
    }

    @Transactional
    public void revoke(UUID projectId, UUID tenantId, UUID keyId) {
        projectService.getForTenant(projectId, tenantId);
        ApiKey key = apiKeyRepository.findById(keyId)
            .filter(k -> projectId.equals(k.getProjectId()))
            .orElseThrow(() -> new ResourceNotFoundException("API key not found: " + keyId));
        key.setActive(false);
        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);
        log.info("Revoked API key id={} project={}", keyId, projectId);
    }

    @Transactional(readOnly = true)
    public ApiKey resolve(String rawKey) {
        String hash = sha256(rawKey);
        ApiKey key = apiKeyRepository.findByKeyHashAndActiveTrue(hash)
                .orElseThrow(InvalidApiKeyException::new);
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) {
            log.warn("API key id={} has expired at={}", key.getId(), key.getExpiresAt());
            throw new InvalidApiKeyException();
        }
        return key;
    }

    @Transactional
    public void recordLastUsed(java.util.UUID keyId) {
        apiKeyRepository.findById(keyId).ifPresent(k -> {
            k.setLastUsedAt(Instant.now());
            apiKeyRepository.save(k);
        });
    }

    private ApiKeyView toView(ApiKey k) {
        return ApiKeyView.builder()
            .id(k.getId())
            .projectId(k.getProjectId())
            .keyPrefix(k.getKeyPrefix())
            .description(k.getDescription())
            .active(k.isActive())
            .createdAt(k.getCreatedAt())
            .revokedAt(k.getRevokedAt())
            .build();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
