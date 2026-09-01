package com.aetherops.escalation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EscalationExecutionRepository extends JpaRepository<EscalationExecution, UUID> {

    Optional<EscalationExecution> findTopByIncidentIdOrderByEscalationLevelDesc(UUID incidentId);

    List<EscalationExecution> findByTenantIdAndIncidentIdOrderByEscalationLevelAsc(UUID tenantId, UUID incidentId);
}
