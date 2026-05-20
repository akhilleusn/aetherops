package com.aetherops.analysis.repository;

import com.aetherops.analysis.model.AIAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AIAnalysisRepository extends JpaRepository<AIAnalysis, UUID> {

    List<AIAnalysis> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    Optional<AIAnalysis> findTopByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    long countByIncidentId(UUID incidentId);

    long countByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AIAnalysis a WHERE a.tenantId = :tenantId AND a.createdAt < :cutoff")
    int deleteByTenantIdAndCreatedAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);
}
