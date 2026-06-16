package com.aetherops.report.repository;

import com.aetherops.report.model.IncidentReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface IncidentReportRepository extends JpaRepository<IncidentReport, UUID> {

    Page<IncidentReport> findByTenantIdOrderByGeneratedAtDesc(UUID tenantId, Pageable pageable);
}
