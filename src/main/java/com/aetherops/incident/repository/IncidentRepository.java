package com.aetherops.incident.repository;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    Page<Incident> findByStatus(IncidentStatus status, Pageable pageable);

    Page<Incident> findByServiceId(String serviceId, Pageable pageable);

    Page<Incident> findByStatusAndServiceId(IncidentStatus status, String serviceId, Pageable pageable);
}
