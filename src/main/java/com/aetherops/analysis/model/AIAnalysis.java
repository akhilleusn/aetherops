package com.aetherops.analysis.model;

import com.aetherops.common.converter.StringListConverter;
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
@Table(name = "ai_analyses")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "likely_cause", columnDefinition = "TEXT")
    private String likelyCause;

    /** Stored as decimal 0.0–1.0. Never stored as integer percent. */
    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @Column(length = 20)
    private String severity;

    @Convert(converter = StringListConverter.class)
    @Column(name = "evidence_refs", columnDefinition = "TEXT")
    private List<String> evidenceRefs;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> unknowns;

    @Convert(converter = StringListConverter.class)
    @Column(name = "recommended_next_checks", columnDefinition = "TEXT")
    private List<String> recommendedNextChecks;

    /** Increments with each analysis run for the same incident. Starts at 1. */
    @Column(name = "analysis_version", nullable = false)
    private int analysisVersion;

    /** Number of evidence items available when this analysis was generated. */
    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    @Column(length = 100)
    private String model;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    /** IDs of resolved incidents used as RAG context. */
    @Convert(converter = StringListConverter.class)
    @Column(name = "similar_incident_ids", columnDefinition = "TEXT")
    private List<String> similarIncidentIds;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
