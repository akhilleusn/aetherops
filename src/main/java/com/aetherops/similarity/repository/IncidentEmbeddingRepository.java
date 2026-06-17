package com.aetherops.similarity.repository;

import com.aetherops.similarity.model.IncidentEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface IncidentEmbeddingRepository extends JpaRepository<IncidentEmbedding, UUID> {

    List<IncidentEmbedding> findByTenantId(UUID tenantId);
}
