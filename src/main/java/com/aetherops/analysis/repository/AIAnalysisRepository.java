package com.aetherops.analysis.repository;

import com.aetherops.analysis.model.AIAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AIAnalysisRepository extends JpaRepository<AIAnalysis, UUID> {

    List<AIAnalysis> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    Optional<AIAnalysis> findTopByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    long countByIncidentId(UUID incidentId);
}
