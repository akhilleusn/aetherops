package com.aetherops.evidence.repository;

import com.aetherops.evidence.model.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, UUID> {

    List<EvidenceItem> findByIncidentIdOrderByTimestampDesc(UUID incidentId);

    long countByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM EvidenceItem e WHERE e.tenantId = :tenantId AND e.createdAt < :cutoff")
    int deleteByTenantIdAndCreatedAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);
}
