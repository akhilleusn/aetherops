package com.aetherops.runbook.model;

import com.aetherops.runbook.converter.RunbookStepListConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "runbook_suggestions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunbookSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Increments with each generation run for the same incident. Starts at 1. */
    @Column(name = "runbook_version", nullable = false)
    private int runbookVersion;

    /** Number of evidence items available when this runbook was generated. */
    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Convert(converter = RunbookStepListConverter.class)
    @Column(columnDefinition = "TEXT", nullable = false)
    private List<RunbookStep> steps;

    /** What data sources were available — e.g. "log-only", "mixed-evidence", "no-evidence". */
    @Column(name = "generation_basis", length = 50)
    private String generationBasis;

    @Column(name = "generator_version", length = 20)
    private String generatorVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
