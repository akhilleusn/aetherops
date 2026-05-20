package com.aetherops.postmortem.repository;

import com.aetherops.postmortem.model.PostmortemDraft;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PostmortemDraftRepository extends JpaRepository<PostmortemDraft, UUID> {

    long countByIncidentId(UUID incidentId);

    Optional<PostmortemDraft> findTopByIncidentIdAndTenantIdOrderByCreatedAtDesc(UUID incidentId, UUID tenantId);

    long countByTenantIdAndCreatedAtBefore(UUID tenantId, Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM PostmortemDraft p WHERE p.tenantId = :tenantId AND p.createdAt < :cutoff")
    int deleteByTenantIdAndCreatedAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);
}
