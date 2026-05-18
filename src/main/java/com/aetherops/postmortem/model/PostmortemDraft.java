package com.aetherops.postmortem.model;

import com.aetherops.common.converter.StringListConverter;
import com.aetherops.postmortem.converter.TimelineEntryListConverter;
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
@Table(name = "postmortem_drafts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostmortemDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Increments with each generation run for the same incident. Starts at 1. */
    @Column(name = "draft_version", nullable = false)
    private int draftVersion;

    /** Number of evidence items available when this draft was generated. */
    @Column(name = "evidence_count", nullable = false)
    private int evidenceCount;

    /** Whether a prior AI analysis was used to enrich likelyCause and dataGaps. */
    @Column(name = "analysis_enriched", nullable = false)
    private boolean analysisEnriched;

    @Column(name = "incident_summary", columnDefinition = "TEXT")
    private String incidentSummary;

    @Convert(converter = TimelineEntryListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<TimelineEntry> timeline;

    @Column(name = "likely_cause", columnDefinition = "TEXT")
    private String likelyCause;

    @Column(columnDefinition = "TEXT")
    private String impact;

    @Convert(converter = StringListConverter.class)
    @Column(name = "data_gaps", columnDefinition = "TEXT")
    private List<String> dataGaps;

    @Convert(converter = StringListConverter.class)
    @Column(name = "follow_up_actions", columnDefinition = "TEXT")
    private List<String> followUpActions;

    @Column(name = "generator_version", length = 20)
    private String generatorVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
