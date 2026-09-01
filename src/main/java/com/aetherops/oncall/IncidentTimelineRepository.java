package com.aetherops.oncall;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentTimelineRepository extends JpaRepository<IncidentTimeline, UUID> {

    List<IncidentTimeline> findByTenantIdAndIncidentIdOrderByCreatedAtAsc(UUID tenantId, UUID incidentId);
}
