package com.aetherops.apikey.repository;

import com.aetherops.apikey.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    List<ApiKey> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);
}
