package com.aetherops.incident.repository;

import com.aetherops.incident.model.IncidentEventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface IncidentEventRepository extends JpaRepository<IncidentEventRecord, UUID> {

    List<IncidentEventRecord> findByIncidentIdOrderBySequenceAsc(UUID incidentId);

    @Query("SELECT COALESCE(MAX(e.sequence), 0) FROM IncidentEventRecord e WHERE e.incidentId = :incidentId")
    Long findMaxSequenceByIncidentId(@Param("incidentId") UUID incidentId);
}
