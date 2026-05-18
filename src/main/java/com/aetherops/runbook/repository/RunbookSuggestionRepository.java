package com.aetherops.runbook.repository;

import com.aetherops.runbook.model.RunbookSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RunbookSuggestionRepository extends JpaRepository<RunbookSuggestion, UUID> {

    long countByIncidentId(UUID incidentId);

    Optional<RunbookSuggestion> findTopByIncidentIdAndTenantIdOrderByCreatedAtDesc(UUID incidentId, UUID tenantId);
}
