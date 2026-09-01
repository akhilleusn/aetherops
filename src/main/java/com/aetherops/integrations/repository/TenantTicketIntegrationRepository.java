package com.aetherops.integrations.repository;

import com.aetherops.integrations.model.TenantTicketIntegration;
import com.aetherops.integrations.model.TicketProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantTicketIntegrationRepository extends JpaRepository<TenantTicketIntegration, UUID> {

    Optional<TenantTicketIntegration> findByTenantIdAndProvider(UUID tenantId, TicketProvider provider);
}
