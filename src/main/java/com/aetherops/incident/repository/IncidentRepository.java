package com.aetherops.incident.repository;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

    List<Incident> findByTenantIdAndStatus(UUID tenantId, IncidentStatus status);

    List<Incident> findByTenantIdAndStatusIn(UUID tenantId, List<IncidentStatus> statuses);

    Optional<Incident> findByTenantIdAndFingerprintAndStatus(
            UUID tenantId, String fingerprint, IncidentStatus status);

    long countByTenantIdAndLastSeenAtBefore(UUID tenantId, Instant cutoff);

    boolean existsByTenantId(UUID tenantId);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM Incident i WHERE i.tenantId = :tenantId AND i.lastSeenAt < :cutoff")
    int deleteByTenantIdAndLastSeenAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);

    @Query("SELECT i FROM Incident i WHERE i.tenantId = :tenantId AND i.serviceId = :serviceId AND i.status = :status AND i.lastSeenAt > :after ORDER BY i.lastSeenAt DESC")
    List<Incident> findRecentOpenByTenantAndService(@Param("tenantId") UUID tenantId,
                                                    @Param("serviceId") String serviceId,
                                                    @Param("status") IncidentStatus status,
                                                    @Param("after") Instant after);

    @Query("SELECT i FROM Incident i WHERE i.tenantId = :tenantId AND i.firstSeenAt >= :from AND i.firstSeenAt < :to")
    List<Incident> findByTenantAndPeriod(@Param("tenantId") UUID tenantId,
                                         @Param("from") Instant from,
                                         @Param("to") Instant to);
}