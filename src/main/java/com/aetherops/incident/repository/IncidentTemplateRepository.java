package com.aetherops.incident.repository;

import com.aetherops.incident.model.IncidentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentTemplateRepository extends JpaRepository<IncidentTemplate, UUID> {

    List<IncidentTemplate> findByTenantIdIsNullOrTenantIdOrderByName(UUID tenantId);

    @Query("SELECT t FROM IncidentTemplate t WHERE t.id = :id AND (t.tenantId IS NULL OR t.tenantId = :tenantId)")
    Optional<IncidentTemplate> findVisibleToTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
