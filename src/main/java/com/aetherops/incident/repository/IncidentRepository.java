package com.aetherops.incident.repository;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    Optional<Incident> findByTenantIdAndFingerprintAndStatus(
        UUID tenantId, String fingerprint, IncidentStatus status);
}
