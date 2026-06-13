package com.aetherops.incident.repository;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    Optional<Incident> findByTenantIdAndFingerprintAndStatus(
            UUID tenantId, String fingerprint, IncidentStatus status);

    long countByTenantIdAndLastSeenAtBefore(UUID tenantId, Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Incident i WHERE i.tenantId = :tenantId AND i.lastSeenAt < :cutoff")
    int deleteByTenantIdAndLastSeenAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);
}