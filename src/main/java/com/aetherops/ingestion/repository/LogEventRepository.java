package com.aetherops.ingestion.repository;

import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.model.LogLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface LogEventRepository extends JpaRepository<LogEvent, UUID> {

    Page<LogEvent> findByTenantId(UUID tenantId, Pageable pageable);

    Page<LogEvent> findByTenantIdAndServiceId(UUID tenantId, String serviceId, Pageable pageable);

    Page<LogEvent> findByTenantIdAndLevel(UUID tenantId, LogLevel level, Pageable pageable);

    Page<LogEvent> findByTenantIdAndServiceIdAndLevel(UUID tenantId, String serviceId, LogLevel level, Pageable pageable);

    long countByTenantIdAndIngestedAtBefore(UUID tenantId, Instant cutoff);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM LogEvent e WHERE e.tenantId = :tenantId AND e.ingestedAt < :cutoff")
    int deleteByTenantIdAndIngestedAtBefore(@Param("tenantId") UUID tenantId, @Param("cutoff") Instant cutoff);
}
