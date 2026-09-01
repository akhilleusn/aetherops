package com.aetherops.branding;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantBrandingRepository extends JpaRepository<TenantBranding, UUID> {
    Optional<TenantBranding> findByTenantId(UUID tenantId);
}
