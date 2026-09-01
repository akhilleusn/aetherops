package com.aetherops.sso;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantSsoConfigRepository extends JpaRepository<TenantSsoConfig, UUID> {
    Optional<TenantSsoConfig> findByTenantId(UUID tenantId);
    Optional<TenantSsoConfig> findByTenantIdAndEnabledTrue(UUID tenantId);
}
