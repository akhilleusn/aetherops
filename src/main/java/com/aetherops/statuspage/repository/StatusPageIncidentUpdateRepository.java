package com.aetherops.statuspage.repository;

import com.aetherops.statuspage.model.StatusPageIncidentUpdate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StatusPageIncidentUpdateRepository extends JpaRepository<StatusPageIncidentUpdate, UUID> {
    List<StatusPageIncidentUpdate> findByIncidentIdOrderByPostedAtAsc(UUID incidentId);
    List<StatusPageIncidentUpdate> findTop50ByTenantIdOrderByPostedAtDesc(UUID tenantId);
}
