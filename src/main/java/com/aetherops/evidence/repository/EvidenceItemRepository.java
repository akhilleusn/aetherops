package com.aetherops.evidence.repository;

import com.aetherops.evidence.model.EvidenceItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EvidenceItemRepository extends JpaRepository<EvidenceItem, UUID> {

    List<EvidenceItem> findByIncidentIdOrderByTimestampDesc(UUID incidentId);
}
