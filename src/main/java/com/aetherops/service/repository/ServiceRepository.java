package com.aetherops.service.repository;

import com.aetherops.service.model.Service;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServiceRepository extends JpaRepository<Service, UUID> {
    List<Service> findByTenantIdAndProjectId(UUID tenantId, UUID projectId);
    Optional<Service> findByTenantIdAndProjectIdAndName(UUID tenantId, UUID projectId, String name);
    List<Service> findByTenantIdAndOwnerTeam(UUID tenantId, String ownerTeam);
}
