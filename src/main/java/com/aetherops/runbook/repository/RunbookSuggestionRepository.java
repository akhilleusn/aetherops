package com.aetherops.runbook.repository;

import com.aetherops.runbook.model.RunbookSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RunbookSuggestionRepository extends JpaRepository<RunbookSuggestion, UUID> {

    long countByIncidentId(UUID incidentId);

    Optional<RunbookSuggestion> findTopByIncidentIdAndTenantIdOrderByCreatedAtDesc(UUID incidentId, UUID tenantId);

    long countByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM RunbookSuggestion r WHERE r.tenantId = :tenantId AND r.createdAt < :cutoff")
    int deleteByTenantIdAndCreatedAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);
}
