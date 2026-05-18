package com.aetherops.analysis.repository;

import com.aetherops.analysis.model.AIAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AIAnalysisRepository extends JpaRepository<AIAnalysis, UUID> {

    List<AIAnalysis> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);
}
