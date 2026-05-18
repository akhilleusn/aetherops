package com.aetherops.postmortem.repository;

import com.aetherops.postmortem.model.PostmortemDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PostmortemDraftRepository extends JpaRepository<PostmortemDraft, UUID> {

    long countByIncidentId(UUID incidentId);

    Optional<PostmortemDraft> findTopByIncidentIdAndTenantIdOrderByCreatedAtDesc(UUID incidentId, UUID tenantId);
}
