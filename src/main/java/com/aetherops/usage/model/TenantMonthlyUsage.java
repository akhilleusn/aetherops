package com.aetherops.usage.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "tenant_monthly_usage",
    uniqueConstraints = @UniqueConstraint(
        name  = "uq_usage_tenant_period",
        columnNames = {"tenant_id", "period"}
    )
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantMonthlyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Calendar month in YYYY-MM format, e.g. "2026-05". */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "ai_analysis_count", nullable = false)
    private int aiAnalysisCount;

    @Column(name = "runbook_generation_count", nullable = false)
    private int runbookGenerationCount;

    @Column(name = "postmortem_generation_count", nullable = false)
    private int postmortemGenerationCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
