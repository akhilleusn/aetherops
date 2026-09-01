package com.aetherops.cost;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantCostSettingsRepository extends JpaRepository<TenantCostSettings, UUID> {
    Optional<TenantCostSettings> findByTenantId(UUID tenantId);
}
