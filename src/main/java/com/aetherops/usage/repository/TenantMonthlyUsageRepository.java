package com.aetherops.usage.repository;

import com.aetherops.usage.model.TenantMonthlyUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantMonthlyUsageRepository extends JpaRepository<TenantMonthlyUsage, UUID> {

    Optional<TenantMonthlyUsage> findByTenantIdAndPeriod(UUID tenantId, String period);
}
