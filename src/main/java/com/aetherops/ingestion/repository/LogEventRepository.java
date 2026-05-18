package com.aetherops.ingestion.repository;

import com.aetherops.ingestion.model.LogEvent;
import com.aetherops.ingestion.model.LogLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LogEventRepository extends JpaRepository<LogEvent, UUID> {

    Page<LogEvent> findByTenantId(UUID tenantId, Pageable pageable);

    Page<LogEvent> findByTenantIdAndServiceId(UUID tenantId, String serviceId, Pageable pageable);

    Page<LogEvent> findByTenantIdAndLevel(UUID tenantId, LogLevel level, Pageable pageable);

    Page<LogEvent> findByTenantIdAndServiceIdAndLevel(UUID tenantId, String serviceId, LogLevel level, Pageable pageable);
}
