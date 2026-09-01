package com.aetherops.cost;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentCostEntryRepository extends JpaRepository<IncidentCostEntry, UUID> {

    List<IncidentCostEntry> findByTenantIdAndIncidentIdOrderByRecordedAtDesc(UUID tenantId, UUID incidentId);

    Optional<IncidentCostEntry> findByTenantIdAndIncidentIdAndSource(UUID tenantId, UUID incidentId, CostSource source);
}
