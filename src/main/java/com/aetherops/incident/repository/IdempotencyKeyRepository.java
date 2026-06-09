package com.aetherops.incident.repository;

import com.aetherops.incident.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    Optional<IdempotencyKey> findByIdempotencyKeyAndTenantIdAndResourceType(
            String idempotencyKey, UUID tenantId, String resourceType);
}